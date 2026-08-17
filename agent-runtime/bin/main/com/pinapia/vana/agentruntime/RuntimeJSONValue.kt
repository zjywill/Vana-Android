package com.pinapia.vana.agentruntime

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * JSON 任意值。JSON Schema 手写起来层数很深,没有字面量便利的话每一层都要包装一层,
 * 读的人根本看不出那本来是一段 JSON。
 */
@Serializable(with = RuntimeJSONValue.Serializer::class)
sealed class RuntimeJSONValue {
    data class StringValue(val value: String) : RuntimeJSONValue()
    data class IntValue(val value: Int) : RuntimeJSONValue()
    data class DoubleValue(val value: Double) : RuntimeJSONValue()
    data class BoolValue(val value: Boolean) : RuntimeJSONValue()
    data class ObjectValue(val value: Map<String, RuntimeJSONValue>) : RuntimeJSONValue()
    data class ArrayValue(val value: List<RuntimeJSONValue>) : RuntimeJSONValue()
    data object NullValue : RuntimeJSONValue()

    val stringValue: String?
        get() = (this as? StringValue)?.value

    val intValue: Int?
        get() = when (this) {
            is IntValue -> value
            is DoubleValue -> value.toInt().takeIf { it.toDouble() == value }
            else -> null
        }

    val doubleValue: Double?
        get() = when (this) {
            is IntValue -> value.toDouble()
            is DoubleValue -> value
            else -> null
        }

    val boolValue: Boolean?
        get() = (this as? BoolValue)?.value

    val objectValue: Map<String, RuntimeJSONValue>?
        get() = (this as? ObjectValue)?.value

    val arrayValue: List<RuntimeJSONValue>?
        get() = (this as? ArrayValue)?.value

    operator fun get(key: String): RuntimeJSONValue? = objectValue?.get(key)

    operator fun get(index: Int): RuntimeJSONValue? {
        val array = arrayValue ?: return null
        return array.getOrNull(index)
    }

        fun encodedString(prettyPrinted: Boolean = false): String {
            @OptIn(ExperimentalSerializationApi::class)
            val json = if (prettyPrinted) {
                Json { prettyPrint = true; prettyPrintIndent = "  " }
            } else {
                Json
            }
            return json.encodeToString(Serializer, this)
        }

    object Serializer : KSerializer<RuntimeJSONValue> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

        override fun serialize(encoder: Encoder, value: RuntimeJSONValue) {
            val jsonEncoder = encoder as? JsonEncoder
                ?: error("RuntimeJSONValue can only be serialized to JSON")
            jsonEncoder.encodeJsonElement(value.toJsonElement())
        }

        override fun deserialize(decoder: Decoder): RuntimeJSONValue {
            val jsonDecoder = decoder as? JsonDecoder
                ?: error("RuntimeJSONValue can only be deserialized from JSON")
            return fromJsonElement(jsonDecoder.decodeJsonElement())
        }
    }

    companion object {
        fun string(value: String): RuntimeJSONValue = StringValue(value)
        fun int(value: Int): RuntimeJSONValue = IntValue(value)
        fun double(value: Double): RuntimeJSONValue = DoubleValue(value)
        fun bool(value: Boolean): RuntimeJSONValue = BoolValue(value)
        fun obj(value: Map<String, RuntimeJSONValue>): RuntimeJSONValue = ObjectValue(value)
        fun arr(value: List<RuntimeJSONValue>): RuntimeJSONValue = ArrayValue(value)
        val NULL: RuntimeJSONValue = NullValue

        fun decode(from: String): RuntimeJSONValue =
            Json.decodeFromString(Serializer, from)

        fun fromJsonElement(element: JsonElement): RuntimeJSONValue = when (element) {
            is JsonNull -> NullValue
            is JsonObject -> ObjectValue(element.mapValues { fromJsonElement(it.value) })
            is JsonArray -> ArrayValue(element.map { fromJsonElement(it) })
            is JsonPrimitive -> when {
                element.isString -> StringValue(element.content)
                element.booleanOrNull != null -> BoolValue(element.booleanOrNull!!)
                element.intOrNull != null -> IntValue(element.intOrNull!!)
                element.doubleOrNull != null -> DoubleValue(element.doubleOrNull!!)
                element.contentOrNull != null -> StringValue(element.content)
                else -> error("Unsupported JSON value")
            }
        }

        private fun RuntimeJSONValue.toJsonElement(): JsonElement = when (this) {
            is StringValue -> JsonPrimitive(value)
            is IntValue -> JsonPrimitive(value)
            is DoubleValue -> JsonPrimitive(value)
            is BoolValue -> JsonPrimitive(value)
            is ObjectValue -> buildJsonObject {
                value.forEach { (k, v) -> put(k, v.toJsonElement()) }
            }
            is ArrayValue -> buildJsonArray {
                value.forEach { add(it.toJsonElement()) }
            }
            NullValue -> JsonNull
        }
    }
}
