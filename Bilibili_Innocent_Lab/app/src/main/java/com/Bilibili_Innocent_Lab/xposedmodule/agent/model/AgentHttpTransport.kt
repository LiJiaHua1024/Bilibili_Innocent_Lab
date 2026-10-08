package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal fun interface AgentHttpTransport {
    fun post(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean): String
}

/** 全程 HTTPS；不跟随重定向；DNS/写入阻塞也不得无限占住任务线程。 */
internal object AgentHttpsTransport : AgentHttpTransport {
    private val workers = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
        Thread(runnable, "BIL-AgentHttp").apply { isDaemon = true }
    }

    override fun post(source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean): String =
        postWithConnection(source, body, timeoutMs, cancelled) { it.openConnection() as HttpURLConnection }

    /** 连接工厂仅用于传输边界的 JVM 验证，生产调用固定为平台 HTTPS 实现。 */
    internal fun postWithConnection(
        source: AgentModelSource, body: ByteArray, timeoutMs: Int, cancelled: () -> Boolean,
        connect: (URL) -> HttpURLConnection
    ): String {
        if (body.size > MAX_REQUEST_BYTES) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
        if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
        val connection = AtomicReference<HttpURLConnection?>()
        val deadline = System.nanoTime() + timeoutMs.coerceIn(1, MAX_TIMEOUT_MS) * 1_000_000L
        val task = try {
            workers.submit(Callable {
                val http = connect(URL(source.resolvedEndpoint))
                connection.set(http)
                try {
                    checkAlive(deadline, cancelled)
                    http.instanceFollowRedirects = false
                    http.useCaches = false
                    http.requestMethod = "POST"
                    http.connectTimeout = remaining(deadline)
                    http.readTimeout = remaining(deadline)
                    http.doOutput = true
                    http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    http.setRequestProperty("Accept", "application/json")
                    http.setRequestProperty("Authorization", "Bearer ${source.apiKey}")
                    http.setFixedLengthStreamingMode(body.size)
                    http.outputStream.use { it.write(body) }
                    checkAlive(deadline, cancelled)
                    val status = http.responseCode
                    if (status in 300..399) throw AgentModelException(AgentModelException.Reason.REDIRECT, status)
                    if (status !in 200..299) {
                        val retryAfter = http.getHeaderField("Retry-After")?.toLongOrNull()
                            ?.coerceIn(0, 300)?.times(1000)
                        val error = http.errorStream?.use { readBounded(it, 16_384, deadline, cancelled) }.orEmpty()
                        throw httpFailure(status, error, retryAfter)
                    }
                    if (http.contentLengthLong > MAX_RESPONSE_BYTES) {
                        throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
                    }
                    http.inputStream.use { readBounded(it, MAX_RESPONSE_BYTES, deadline, cancelled) }
                } finally {
                    http.disconnect()
                    connection.compareAndSet(http, null)
                }
            })
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            throw AgentModelException(AgentModelException.Reason.NETWORK)
        }
        try {
            while (true) {
                checkAlive(deadline, cancelled)
                try {
                    return task.get(minOf(100, remaining(deadline)).toLong(), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // 有界等待同时响应用户取消；真实 Socket 超时只是第二道防线。
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    if (cause is AgentModelException) throw cause
                    if (cause is java.net.SocketTimeoutException) throw AgentModelException(AgentModelException.Reason.TIMEOUT)
                    throw AgentModelException(AgentModelException.Reason.NETWORK)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentModelException(AgentModelException.Reason.CANCELLED)
        } finally {
            task.cancel(true)
            connection.getAndSet(null)?.disconnect()
        }
    }

    private fun readBounded(input: InputStream, limit: Int, deadline: Long, cancelled: () -> Boolean): String {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkAlive(deadline, cancelled)
            val count = input.read(buffer)
            if (count < 0) break
            if (result.size() + count > limit) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
            result.write(buffer, 0, count)
        }
        return result.toString(Charsets.UTF_8.name())
    }

    private fun remaining(deadline: Long): Int =
        ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1, MAX_TIMEOUT_MS.toLong()).toInt()

    private fun checkAlive(deadline: Long, cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw AgentModelException(AgentModelException.Reason.CANCELLED)
        if (System.nanoTime() >= deadline) throw AgentModelException(AgentModelException.Reason.TIMEOUT)
    }

    internal fun httpFailure(status: Int, body: String, retryAfterMs: Long?): AgentModelException {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code").orEmpty()
        val parameter = error?.optString("param").orEmpty()
        val parameterRejected = status in setOf(400, 422) && code in setOf("unsupported_parameter", "invalid_request_error")
        val optionalParameter = parameter.substringBefore('.')
        // 只认结构化拒绝，不能从任意错误正文推断能力或把正文展示给用户。
        val reason = when {
            parameterRejected &&
                parameter in setOf("max_tokens", "max_completion_tokens") -> AgentModelException.Reason.TOKEN_PARAMETER
            parameterRejected && optionalParameter in setOf("enable_thinking", "thinking", "reasoning") &&
                parameter in setOf("enable_thinking", "thinking", "thinking.type", "reasoning", "reasoning.effort") ->
                AgentModelException.Reason.OPTIONAL_PARAMETER
            status in setOf(400, 422) && code == "unsupported_feature" && parameter == "tools" ->
                AgentModelException.Reason.TOOLS_UNSUPPORTED
            status in setOf(400, 422) && code == "unsupported_feature" && parameter in setOf("image_url", "vision") ->
                AgentModelException.Reason.VISION_UNSUPPORTED
            else -> AgentModelException.Reason.HTTP
        }
        return AgentModelException(reason, status, retryAfterMs,
            if (reason == AgentModelException.Reason.OPTIONAL_PARAMETER) optionalParameter else null)
    }

    const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 512 * 1024
    const val MAX_TIMEOUT_MS = 120_000
}
