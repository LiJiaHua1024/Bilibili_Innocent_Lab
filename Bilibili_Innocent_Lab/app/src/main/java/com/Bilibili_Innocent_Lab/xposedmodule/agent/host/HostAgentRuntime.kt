// TTL 回调按任务 generation 释放 Binder 绑定和 Window 所有权，保留现有 Handler 取消语义。
@file:Suppress("ReplaceWithCoroutinesExtension")

package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentWire
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentTaskCache
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** 只由已完成授权的主进程安装链初始化；与诊断查询使用不同 Binder 和事务协议。 */
internal object HostAgentRuntime {
    @Volatile private var runtime: Runtime? = null

    @Synchronized
    fun initialize(context: Context, classLoader: ClassLoader) {
        if (runtime != null) return
        val application = (context.applicationContext ?: context) as? Application ?: return
        if (application.packageName != AgentWire.TARGET_PACKAGE) return
        runtime = Runtime(application, classLoader)
    }

    fun endpoint(): IBinder? = runtime?.endpoint

    private class Runtime(private val app: Application, private val loader: ClassLoader) {
        private val main = Handler(Looper.getMainLooper())
        private val session = HostAgentSession(AgentWire.MAX_LEASE_MS, AgentWire.IPC_TIMEOUT_MS)
        private val admissionLock = Any()
        private val operations = executor("BIL-AgentHost", 4)
        private val responses = executor("BIL-AgentReply", 16)
        private val windows = HostAgentWindow(app, main, session, ::stop)
        private val rpc = HostAgentRpc(loader)
        private val router by lazy { HostAgentRouter.resolve(loader) }
        @Volatile private var moduleUid = -1
        private var binding: Binding? = null // 主线程所有
        private var expiry: Expiry? = null // 一个任务只保留一个可重排的 TTL 回调
        private val cacheLock = Any()
        private val detailCache = AgentTaskCache(AgentTaskCache.DETAIL_CAPACITY, AgentTaskCache.DETAIL_TTL_MS)
        private var cacheGeneration: Long? = null // cacheLock 所有；不在锁内执行 RPC

        init {
            operations.execute {
                moduleUid = runCatching {
                    app.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
                        .takeIf { it >= 10_000 && it != android.os.Process.myUid() } ?: -1
                }.getOrDefault(-1)
            }
        }

        val endpoint = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != AgentWire.REQUEST) return super.onTransact(code, data, reply, flags)
                // 每笔核验平台给出的 UID；载荷里的身份与 shell/root 都不能代替模块身份。
                if (moduleUid < 0 || getCallingUid() != moduleUid ||
                    flags and IBinder.FLAG_ONEWAY == 0 || data.dataSize() > AgentWire.MAX_REQUEST_BYTES) return false
                return runCatching {
                    data.enforceInterface(AgentWire.DESCRIPTOR)
                    val task = data.readString().orEmpty()
                    val sequence = data.readLong()
                    val deadline = data.readLong()
                    val operation = data.readString().orEmpty()
                    val raw = data.readString().orEmpty()
                    val callback = data.readStrongBinder() ?: return@runCatching false
                    if (data.dataAvail() != 0 || task.length > 96 || operation.length > 40 || raw.length > 12_000) return@runCatching false
                    val request = Request(task, sequence, deadline, operation, callback)
                    val arguments = runCatching { JSONObject(raw) }.getOrNull()
                    if (arguments == null || !validArguments(operation, arguments)) {
                        deliver(request, failure("invalid_arguments")); return@runCatching true
                    }
                    synchronized(admissionLock) {
                        val closing = operation == "cancel" || operation == "finish"
                        val admission = when (operation) {
                            "begin" -> session.begin(task, sequence, deadline, arguments.getLong("lease_until"), now(), arguments.optBoolean("allow_vision", false))
                            "renew" -> session.renew(task, sequence, deadline, arguments.getLong("lease_until"), now())
                            else -> session.admit(task, sequence, deadline, now(), closing)
                        }
                        val lease = admission.lease
                        if (lease == null) {
                            deliver(request, failure(admission.error ?: "task_inactive"))
                        } else if (closing) {
                            // 即使 RPC 正在阻塞，取消也在收包线程立刻使租约失效。
                            main.post { release(lease) }
                            deliver(request, success(JSONObject().put("status", if (operation == "cancel") "cancelled" else "finished")))
                        } else {
                            try {
                                operations.execute { execute(request, arguments, lease) }
                            } catch (_: java.util.concurrent.RejectedExecutionException) {
                                if (operation == "begin" || operation == "renew") stop(lease)
                                deliver(request, failure("host_queue_busy"))
                            }
                        }
                    }
                    true
                }.getOrDefault(false)
            }
        }

        private fun execute(request: Request, args: JSONObject, lease: HostAgentSession.Lease) {
            val value = try {
                if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
                windows.verifyAccessibilityControl(lease)
                val result = when (request.operation) {
                    "begin" -> {
                        windows.awaitTaskWindow(lease)
                        onMain(lease) {
                            if (!bind(lease)) throw HostAgentFailure("module_service_unavailable")
                            scheduleExpiry(lease)
                        }
                        synchronized(cacheLock) { cacheGeneration = lease.generation; detailCache.clear() }
                        JSONObject().put("status", "started").put("deadline_elapsed", session.current(now())?.deadline)
                    }
                    "renew" -> {
                        // 续租必须复核前台控制权；允许等待已发起的有限导航，但不能接管其它页面。
                        windows.awaitTaskContext(lease)
                        onMain(lease) { scheduleExpiry(lease) }
                        JSONObject().put("status", "renewed").put("deadline_elapsed", session.current(now())?.deadline)
                    }
                    "capabilities" -> JSONObject().put("search_videos", rpc.search != null && router != null)
                        .put("pagination", rpc.search?.canPaginate == true)
                        .put("get_video_details", rpc.details != null).put("owner_verification", rpc.details?.canVerifyOwner == true)
                        .put("open_video", router != null).put("get_host_state", true)
                        .put("inspect_screen", session.allowVision(lease, now()))
                        .put("screen_requires_task_page", true).put("account_write_actions", false)
                    "get_host_state" -> onMain(lease) { windows.state(lease) }
                    "search_videos" -> search(lease, args)
                    "get_video_details" -> {
                        val id = knownId(lease, args)
                        onMain(lease) { windows.requireTaskContext(lease) }
                        val key = "${lease.generation}:$id"
                        val cached = synchronized(cacheLock) {
                            if (cacheGeneration != lease.generation) { detailCache.clear(); cacheGeneration = lease.generation }
                            detailCache.get(key, now())
                        }
                        cached ?: (rpc.details ?: throw HostAgentFailure("video_details_unavailable"))
                            .query(id, (lease.deadline - now()).coerceAtLeast(1)).also { value ->
                                val observedAt = now()
                                value.put("cache_hit", false).put("observed_at_elapsed", observedAt)
                                if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
                                synchronized(cacheLock) {
                                    if (cacheGeneration != lease.generation) throw HostAgentFailure("task_inactive")
                                    detailCache.put(key, value, observedAt)
                                }
                            }
                    }
                    "open_video" -> {
                        val id = knownId(lease, args)
                        navigate(lease, checkNotNull(HostAgentNavigationPolicy.videoRoute(id)), video = id)
                        JSONObject().put("video_id", id).put("navigation", "requested").put("observed", false)
                    }
                    "inspect_screen" -> windows.capture(lease)
                    else -> throw HostAgentFailure("unsupported_operation")
                }
                success(result)
            } catch (caught: HostAgentFailure) {
                if (request.operation == "begin" || request.operation == "renew") stop(lease)
                failure(caught.reason)
            } catch (_: Throwable) {
                if (request.operation == "begin" || request.operation == "renew") stop(lease)
                failure("host_operation_failed")
            }
            deliver(request, value, lease)
        }

        private fun search(lease: HostAgentSession.Lease, args: JSONObject): JSONObject {
            val query = HostAgentNavigationPolicy.searchQuery(args.optString("query")) ?: throw HostAgentFailure("invalid_query")
            val cursorToken = if (args.isNull("cursor")) null else args.optString("cursor").takeIf { it.isNotBlank() }
            val cursor = cursorToken?.let { session.cursor(lease, query, it, now()) ?: throw HostAgentFailure("invalid_cursor") }
            val search = rpc.search ?: throw HostAgentFailure("search_unavailable")
            if (cursor != null && !search.canPaginate) throw HostAgentFailure("pagination_unavailable")
            // 后续 RPC 分页不假装推动可见列表的滚动；响应明确标注数据来自后台查询。
            if (cursor == null) navigate(lease, checkNotNull(HostAgentNavigationPolicy.searchRoute(query)), query = query)
            else onMain(lease) { windows.requireTaskContext(lease) }
            val result = search.query(query, cursor, (lease.deadline - now()).coerceAtLeast(1))
            if (!session.isActive(lease, now())) throw HostAgentFailure("task_inactive")
            val permitted = session.remember(lease, result.videos.map { it.id }, now()).toSet()
            val videos = JSONArray()
            result.videos.filter { it.id in permitted }.forEach { videos.put(it.json()) }
            val next = session.rememberCursor(lease, query, result.next, now())
            return JSONObject().put("query", query).put("source", "host_rpc").put("videos", videos)
                .put("navigation", if (cursor == null) "requested" else "unchanged")
                .put("truncated_by_task_budget", result.videos.any { it.id !in permitted })
                .put("candidate_window", HostAgentSession.MAX_CANDIDATES).put("cursor_window", HostAgentSession.MAX_CURSORS)
                .put("visible_results_verified", false).put("next_cursor", next ?: JSONObject.NULL)
        }

        private fun knownId(lease: HostAgentSession.Lease, args: JSONObject): String {
            val id = HostAgentNavigationPolicy.videoId(args.optString("video_id")) ?: throw HostAgentFailure("invalid_video_id")
            if (!session.knows(lease, id, now())) throw HostAgentFailure("video_not_in_task")
            return id
        }

        private fun navigate(lease: HostAgentSession.Lease, uri: String, query: String? = null, video: String? = null) {
            val resolved = router ?: throw HostAgentFailure("navigation_unavailable")
            onMain(lease) {
                val activity = windows.prepareNavigation(lease, query, video)
                try { resolved.open(activity, uri) }
                catch (failure: Throwable) { windows.navigationFailed(lease); throw failure }
            }
        }

        private fun <T> onMain(lease: HostAgentSession.Lease, action: () -> T): T {
            val future = CompletableFuture<T>()
            main.post {
                if (future.isDone) return@post
                try {
                    val value = session.whileActive(lease, now()) {
                        if (future.isDone) throw HostAgentFailure("main_thread_timeout")
                        action()
                    } ?: throw HostAgentFailure("task_inactive")
                    future.complete(value)
                } catch (failure: Throwable) { future.completeExceptionally(failure) }
            }
            return try { future.get(minOf(2000L, (lease.deadline - now()).coerceAtLeast(1)), TimeUnit.MILLISECONDS) }
            catch (failure: Exception) {
                future.cancel(false)
                throw failure.cause as? HostAgentFailure ?: HostAgentFailure("main_thread_timeout")
            }
        }

        private fun stop(lease: HostAgentSession.Lease) {
            session.cancel(lease, now())
            main.post { release(lease) }
        }

        /** 最新 task deadline 决定 TTL；旧 command deadline 不影响长期窗口监听，也不获得延长。 */
        private fun scheduleExpiry(lease: HostAgentSession.Lease) {
            expiry?.let { previous ->
                if (previous.generation == lease.generation) return
                main.removeCallbacks(previous.callback)
            }
            val callback = object : Runnable {
                override fun run() {
                    try {
                        val current = session.current(now())
                        if (current == null || current.generation != lease.generation) {
                            release(lease)
                            return
                        }
                        main.postDelayed(this, (current.deadline - now()).coerceAtLeast(1))
                    } catch (_: Throwable) { stop(lease) }
                }
            }
            expiry = Expiry(lease.generation, callback)
            val current = session.current(now()) ?: return
            main.postDelayed(callback, (current.deadline - now()).coerceAtLeast(1))
        }

        /** Activity 已可见后才绑定；不请求后台启动权限，不永久保活模块。 */
        private fun bind(lease: HostAgentSession.Lease): Boolean {
            binding?.let { release(it.lease) }
            val component = ComponentName(BuildConfig.APPLICATION_ID,
                "com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentSessionService")
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (name != component || !session.isTaskActive(lease, now())) { stop(lease); return }
                    try {
                        responses.execute {
                            if (!session.isTaskActive(lease, now())) return@execute
                            val data = Parcel.obtain()
                            try {
                                data.writeInterfaceToken(AgentWire.SERVICE_DESCRIPTOR)
                                data.writeString(lease.taskId)
                                if (!service.transact(AgentWire.SERVICE_KEEP_ALIVE, data, null, IBinder.FLAG_ONEWAY)) stop(lease)
                            } catch (_: Throwable) { stop(lease) } finally { data.recycle() }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { stop(lease) }
                }
                override fun onServiceDisconnected(name: ComponentName) { stop(lease) }
                override fun onBindingDied(name: ComponentName) { stop(lease) }
                override fun onNullBinding(name: ComponentName) { stop(lease) }
            }
            val accepted = runCatching { app.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
            if (accepted) binding = Binding(lease, connection)
            return accepted
        }

        private fun release(lease: HostAgentSession.Lease) {
            expiry?.takeIf { it.generation == lease.generation }?.let {
                main.removeCallbacks(it.callback)
                expiry = null
            }
            val current = binding
            if (current != null && current.lease.generation == lease.generation) {
                binding = null
                runCatching { app.unbindService(current.connection) }
            }
            // 窗口层也按 generation 回收，旧任务迟到的 TTL/Service 回调不能覆盖新任务 callback。
            windows.releaseTask(lease)
            val taskClosed = !session.isTaskActive(lease, now())
            synchronized(cacheLock) {
                // 缓存只有 JSON 文本；清理本身是短锁，不等待 operations 中的网络查询。
                if (cacheGeneration == lease.generation && taskClosed) {
                    detailCache.clear()
                    cacheGeneration = null
                }
            }
        }

        private fun deliver(request: Request, value: JSONObject, lease: HostAgentSession.Lease? = null) {
            try {
                responses.execute {
                    // 取消后绝不交付成功结果（尤其截图）；已收敛的失败原因仍可用于解释禁用边界。
                    val activeValue = if (lease != null && value.optBoolean("ok")) {
                        if (!session.isActive(lease, now())) failure("task_inactive") else try {
                            windows.verifyAccessibilityControl(lease)
                            value
                        } catch (caught: HostAgentFailure) { failure(caught.reason) }
                    } else value
                    var data = Parcel.obtain()
                    try {
                        fun write(parcel: Parcel, json: JSONObject) {
                            parcel.writeInterfaceToken(AgentWire.DESCRIPTOR)
                            parcel.writeString(request.task)
                            parcel.writeLong(request.sequence)
                            parcel.writeString(json.toString())
                        }
                        write(data, activeValue)
                        if (data.dataSize() > AgentWire.MAX_RESPONSE_BYTES) {
                            data.recycle(); data = Parcel.obtain()
                            write(data, failure("response_too_large"))
                        }
                        request.callback.transact(AgentWire.RESPONSE, data, null, IBinder.FLAG_ONEWAY)
                    } catch (_: Throwable) { /* 模块回收/取消时不重试，避免执行或回传重复结果。 */ }
                    finally { data.recycle() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { /* 有界背压，由模块请求超时结束。 */ }
        }

        private data class Binding(val lease: HostAgentSession.Lease, val connection: ServiceConnection)
        private data class Expiry(val generation: Long, val callback: Runnable)
        private data class Request(val task: String, val sequence: Long, val deadline: Long, val operation: String, val callback: IBinder)
    }

    private fun validArguments(operation: String, args: JSONObject): Boolean {
        val permitted = when (operation) {
            "begin" -> setOf("allow_vision", "lease_until")
            "renew" -> setOf("lease_until")
            "capabilities", "get_host_state", "inspect_screen", "cancel", "finish" -> emptySet()
            "search_videos" -> setOf("query", "cursor")
            "get_video_details", "open_video" -> setOf("video_id")
            else -> return false
        }
        if (args.keys().asSequence().any { it !in permitted }) return false
        return when (operation) {
            "begin" -> (args.opt("lease_until") is Long || args.opt("lease_until") is Int) &&
                (!args.has("allow_vision") || args.opt("allow_vision") is Boolean)
            "renew" -> args.opt("lease_until") is Long || args.opt("lease_until") is Int
            "search_videos" -> args.opt("query") is String && HostAgentNavigationPolicy.searchQuery(args.getString("query")) != null &&
                (!args.has("cursor") || args.isNull("cursor") || (args.opt("cursor") is String && args.getString("cursor").length <= 32))
            "get_video_details", "open_video" -> args.opt("video_id") is String && HostAgentNavigationPolicy.videoId(args.getString("video_id")) != null
            else -> true
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun success(data: JSONObject) = JSONObject().put("ok", true).put("data", data)
    private fun failure(reason: String) = JSONObject().put("ok", false).put("error", reason)
    private fun executor(name: String, capacity: Int) = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(capacity), { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
}
