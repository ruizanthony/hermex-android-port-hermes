package com.uzairansar.hermex.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Server evidence only; configuration and route attempts are not observed output. */
@Serializable
data class RuntimeModelSnapshot(
    @Serializable(with = RuntimeNullableStringSerializer::class)
    @SerialName("session_id") val sessionId: String? = null,
    @Serializable(with = RuntimeNullableStringSerializer::class)
    @SerialName("stream_id") val streamId: String? = null,
    @Serializable(with = RuntimeNullableStringSerializer::class)
    val model: String? = null,
    @Serializable(with = RuntimeNullableStringSerializer::class)
    val provider: String? = null,
    @Serializable(with = RuntimeNullableBooleanSerializer::class)
    @SerialName("fallback_active") val fallbackActive: Boolean? = null,
    @Serializable(with = RuntimeNullableStringSerializer::class)
    val phase: String? = null,
) {
    fun validFor(sessionId: String?, streamId: String?): Boolean =
        !sessionId.isNullOrBlank() && !streamId.isNullOrBlank() &&
            this.sessionId == sessionId && this.streamId == streamId &&
            !model.isNullOrBlank() && phase in setOf("observed_output", "route_observed")

    val hasObservedOutput: Boolean get() = phase == "observed_output" && !model.isNullOrBlank()
}

@Serializable
data class RuntimeJournalSnapshot(
    @SerialName("runtime_model") val runtimeModel: RuntimeModelSnapshot? = null,
)

/** Wrong-typed optional evidence is absent, never coerced into an identity. */
object RuntimeNullableStringSerializer : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("RuntimeNullableString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String? =
        ((decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    override fun serialize(encoder: Encoder, value: String?) =
        (encoder as JsonEncoder).encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
}

object RuntimeNullableBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor = PrimitiveSerialDescriptor("RuntimeNullableBoolean", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean? =
        ((decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    override fun serialize(encoder: Encoder, value: Boolean?) =
        (encoder as JsonEncoder).encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
}

object RuntimeJournalSnapshotSerializer : KSerializer<RuntimeJournalSnapshot?> {
    override val descriptor = RuntimeJournalSnapshot.serializer().descriptor
    override fun deserialize(decoder: Decoder): RuntimeJournalSnapshot? {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement()
        return runCatching { json.json.decodeFromJsonElement<RuntimeJournalSnapshot>(element) }.getOrNull()
    }
    override fun serialize(encoder: Encoder, value: RuntimeJournalSnapshot?) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(value?.let { json.json.encodeToJsonElement(it) } ?: JsonNull)
    }
}
