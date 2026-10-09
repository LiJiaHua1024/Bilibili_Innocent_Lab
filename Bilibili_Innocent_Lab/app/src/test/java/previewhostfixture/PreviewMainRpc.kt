package previewhostfixture

import kotlin.coroutines.Continuation

class MainListReply
class MainListReq

object PreviewMainRpc {
    @JvmStatic fun suspendMainList(moss: ReplyMoss, request: MainListReq, continuation: Continuation<*>): Any = request
}

class WrongPreviewFetch {
    @JvmField var label = 1
    fun invokeSuspend(value: Any): Any = value
}
