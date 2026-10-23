package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.InputFilter
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.highcapable.betterandroid.ui.extension.component.base.toPx
import com.highcapable.betterandroid.ui.extension.view.textToString
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal fun insightText(context: Context, chinese: String, english: String, traditional: String = chinese): String =
    when (InjectedUiLocale.resolveEffectiveTag(context)) { "en" -> english; "zh-Hant" -> traditional; else -> chinese }

/** 只属于简介 Fragment 的卡片；播放器区不加浮层，章节列表交给宿主原生 UI。 */
internal class InsightCard(context: Context, private val repository: InsightRepository?,
    private val automaticChapters: Boolean, private val nativeChaptersAvailable: Boolean,
    private val chaptersReady: (InsightVideo, InsightCache.Entry) -> Unit) : LinearLayout(context) {
    private val revision = AtomicLong()
    private var session: InsightPlayerSession? = null
    private var summary: InsightCache.Entry? = null
    private val history = ArrayDeque<Pair<String, String>>()
    private var dialog: Dialog? = null
    private var busy = false
    private val title = TextView(context)
    private val body = TextView(context)
    private val question = Button(context)
    private val comments = Button(context)
    private val chapters = Button(context)
    private val ink = body.currentTextColor
    init {
        orientation = VERTICAL
        setPadding(12.toPx(context), 8.toPx(context), 12.toPx(context), 8.toPx(context))
        background = GradientDrawable().apply { setColor(Color.argb(16, 0, 150, 160)); cornerRadius = 12.toPx(context).toFloat() }
        title.text = insightText(context, "AI Insight · 视频总结", "AI Insight · Summary", "AI Insight · 影片總結")
        title.textSize = 15f; title.setTypeface(null, Typeface.BOLD)
        title.isFocusable = true
        title.setTextColor(ink)
        title.setPadding(0, 4.toPx(context), 0, 4.toPx(context))
        addView(title, LayoutParams(-1, -2))
        body.textSize = 13f; body.maxLines = 4
        body.movementMethod = LinkMovementMethod.getInstance()
        body.text = insightText(context, "等待当前视频…", "Waiting for video…", "等待目前影片…")
        addView(body, LayoutParams(-1, -2))
        val actions = LinearLayout(context)
        fun action(button: Button, label: String, run: () -> Unit) {
            button.text = label; button.textSize = 12f; button.isAllCaps = false
            button.minWidth = 0; button.minimumWidth = 0
            button.setOnClickListener { HostThreadGuard.run("insight.action", run) }
            actions.addView(button, LayoutParams(0, -2, 1f))
        }
        action(question, "Ask") { open(InsightMode.ASK) }
        action(comments, insightText(context, "评论话题", "Topics", "評論話題")) { open(InsightMode.COMMENTS) }
        action(chapters, insightText(context, "AI 章节", "Chapters", "AI 章節")) { requestChapters() }
        addView(actions, LayoutParams(-1, -2))
        title.setOnClickListener { HostThreadGuard.run("insight.summary.expand") {
            if (summary != null) open(InsightMode.SUMMARY) else loadSummary()
        } }
        updateActions()
    }

    fun bind(value: InsightPlayerSession?) {
        val previous = session
        session = value
        if (previous?.video != value?.video || value == null) {
            revision.incrementAndGet(); busy = false; pending = null; dialog?.dismiss(); dialog = null
            if (previous?.video != value?.video) { summary = null; history.clear() }
        }
        updateActions()
        if (value != null && summary == null && !busy) loadSummary()
    }
    fun dispose() { bind(null); pending = null }

    private fun updateActions() {
        val available = repository != null && session != null && !busy
        question.isEnabled = available; comments.isEnabled = available; chapters.isEnabled = available && nativeChaptersAvailable
        if (repository == null) body.text = insightText(context,
            "请先在模块 AI 配置中填写 HTTPS 对话来源（OpenAI 兼容或 Cloudflare；JEV 不支持生成）。", "Configure an HTTPS chat source in AI settings (OpenAI compatible or Cloudflare; JEV cannot generate text).",
            "請先在模組 AI 設定中填寫 HTTPS 對話來源（OpenAI 相容或 Cloudflare；JEV 不支援生成）。")
    }
    private fun loadSummary() {
        val current = session ?: return
        if (repository == null || busy) return
        body.text = insightText(context, "正在读取字幕并总结…", "Reading subtitles and summarizing…", "正在讀取字幕並總結…")
        request(InsightMode.SUMMARY, current) { result ->
            summary = result
            body.text = render(result)
            if (nativeChaptersAvailable && automaticChapters && result.timed && result.duration >= 600) requestChapters(silent = true)
        }
    }
    private fun requestChapters(silent: Boolean = false) {
        val current = session ?: return
        request(InsightMode.CHAPTERS, current, failure = if (silent) null else body) { result ->
            chaptersReady(current.video, result)
            if (!silent) body.text = insightText(context,
                "AI 章节已缓存，将随宿主下次进度信息刷新或重新打开此分 P 时显示于原生章节中。", "Chapters saved for the next native progress refresh or reopening.",
                "AI 章節已快取，將隨宿主下次進度資訊重新整理或重新開啟此分 P 時顯示於原生章節中。")
        }
    }
    private fun open(mode: InsightMode) {
        val current = session ?: return
        if (!current.valid()) return
        dialog?.dismiss()
        val window = Dialog(context)
        val column = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(16.toPx(context), 12.toPx(context), 16.toPx(context), 12.toPx(context))
        }
        val heading = TextView(context).apply {
            text = when (mode) { InsightMode.ASK -> "Ask · AI Insight"; InsightMode.COMMENTS -> insightText(context, "评论区在聊什么", "Comment topics", "評論區在聊甚麼"); else -> title.text }
            textSize = 17f; setTypeface(null, Typeface.BOLD)
        }
        column.addView(heading, LayoutParams(-1, -2))
        val transcript = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply { addView(transcript) }
        column.addView(scroll, LayoutParams(-1, 0, 1f))
        fun line(text: CharSequence): TextView = TextView(context).apply {
            this.text = text; textSize = 14f; movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, 10.toPx(context), 0, 10.toPx(context))
        }.also { transcript.addView(it, LayoutParams(-1, -2)) }
        if (mode == InsightMode.SUMMARY) summary?.let { line(render(it)) }
        if (mode == InsightMode.COMMENTS) {
            val output = line(insightText(context, "正在概括热评讨论…", "Summarizing sampled comments…", "正在概括熱門評論…"))
            request(mode, current, failure = output) { output.text = render(it) }
        }
        if (mode == InsightMode.ASK) {
            for ((q, a) in history) { line(q); line(render(InsightCache.Entry(InsightOutput(a), summary?.duration ?: 0, summary?.timed == true))) }
            val row = LinearLayout(context)
            val field = EditText(context).apply {
                hint = insightText(context, "针对视频提问…", "Ask about this video…", "針對影片提問…")
                filters = arrayOf(InputFilter.LengthFilter(2000)); maxLines = 3
            }
            val send = Button(context).apply { text = insightText(context, "发送", "Send", "傳送"); isAllCaps = false }
            row.addView(field, LayoutParams(0, -2, 1f)); row.addView(send, LayoutParams(-2, -2)); column.addView(row)
            send.setOnClickListener { HostThreadGuard.run("insight.ask") {
                val query = field.textToString().trim()
                if (query.isNotEmpty() && !busy) {
                    line(query); field.setText("")
                    val answer = line(insightText(context, "正在回答…", "Thinking…", "正在回答…"))
                    request(InsightMode.ASK, current, query, answer) { entry ->
                        answer.text = render(entry); history.addLast(query to entry.output.text)
                        while (history.size > 6) history.removeFirst()
                    }
                }
            } }
        }
        window.setContentView(column)
        window.setOnDismissListener { if (dialog === window) dialog = null }
        dialog = window
        window.show()
        window.window?.setLayout((context.resources.displayMetrics.widthPixels * .94).toInt(),
            (context.resources.displayMetrics.heightPixels * .60).toInt())
    }

    private fun request(mode: InsightMode, current: InsightPlayerSession, query: String = "", failure: TextView? = body,
        completed: (InsightCache.Entry) -> Unit) {
        val repo = repository ?: return
        if (busy || !current.valid()) return
        busy = true; updateActions()
        val token = revision.get(); val weak = WeakReference(this); val past = history.toList()
        val failedView = WeakReference(failure)
        // completed 只短暂由主线程回调持有；后台只持有卡片弱引用及纯数据。
        pending = completed
        fun deliver(outcome: Result<InsightCache.Entry>) {
            val card = weak.get() ?: return
            card.post(HostThreadGuard.runnable("insight.result") {
                if (card.revision.get() != token || card.session?.video != current.video) return@runnable
                card.busy = false; card.updateActions()
                val callback = card.pending; card.pending = null
                outcome.onSuccess { callback?.invoke(it) }.onFailure {
                    failedView.get()?.text = insightText(card.context,
                        "暂时无法生成：字幕、评论或接口不可用，或回答格式无效。点标题重试；自动章节仅适用于无官方章节的 10 分钟以上字幕视频。",
                        "Could not generate. Context or model unavailable, or invalid response. Tap the title to retry. Chapters require subtitles, at least 10 minutes, and no official chapters.",
                        "暫時無法生成：字幕、評論或介面不可用，或回答格式無效。點標題重試。自動章節僅適用於無官方章節的 10 分鐘以上字幕影片。")
                }
            })
        }
        // 独立的短任务队列只读缓存；另一个视频的慢网络请求不会挡住已缓存总结。
        val submitted = runCatching { cacheWorker.execute {
            if (weak.get()?.revision?.get() != token) return@execute
            val cached = runCatching { repo.cached(current.video, mode) }.getOrNull()
            if (cached != null) deliver(Result.success(cached)) else runCatching { worker.execute {
                deliver(runCatching { repo.generate(current.video, mode, query, past) {
                    weak.get()?.revision?.get() != token
                } })
            } }.onFailure { deliver(Result.failure(it)) }
        } }.isSuccess
        if (!submitted) { busy = false; pending = null; updateActions(); failure?.text = insightText(context, "请求队列已满，请稍后重试。", "Queue full. Try again shortly.", "請求佇列已滿，請稍後重試。") }
    }
    private var pending: ((InsightCache.Entry) -> Unit)? = null
    private fun render(entry: InsightCache.Entry): CharSequence {
        val text = entry.output.text
        val builder = SpannableStringBuilder()
        var offset = 0
        for (link in InsightTimestamp.links(text, entry.duration, entry.timed)) {
            builder.append(text.substring(offset, link.start))
            val start = builder.length; builder.append(InsightTimestamp.label(link.second))
            val targetVideo = session?.video
            builder.setSpan(object : ClickableSpan() { override fun onClick(widget: View) {
                HostThreadGuard.run("insight.timestamp") {
                    val current = session
                    if (current != null && current.video == targetVideo && current.valid()) {
                        if (current.seek(link.second)) dialog?.dismiss()
                    }
                }
            } }, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            offset = link.end
        }
        builder.append(text.substring(offset))
        if (!entry.timed) builder.insert(0, insightText(context,
            "无可用字幕：仅依据标题、简介及讨论内容，不提供情节定位。\n\n",
            "No subtitles: based on metadata and discussion only. Scene timestamps are unavailable.\n\n",
            "無可用字幕：僅依據標題、簡介及討論內容，不提供情節定位。\n\n"))
        return builder
    }
    companion object {
        private val cacheWorker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { Thread(it, "BIL-InsightRead").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
        private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { Thread(it, "BIL-Insight").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    }
}
