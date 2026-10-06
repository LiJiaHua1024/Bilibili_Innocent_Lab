package kmossfixture

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf

class KReplyMoss {
    interface Handler { fun onNext(reply: Any?) }
    @Suppress("UNUSED_PARAMETER")
    fun mainList(request: Any, serializer: SerializationStrategy<Any>, decoder: DeserializationStrategy<Any>,
        handler: Handler, protoBuf: ProtoBuf) = Unit
}
