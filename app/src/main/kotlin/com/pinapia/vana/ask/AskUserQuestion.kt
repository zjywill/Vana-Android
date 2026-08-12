package com.pinapia.vana.ask

import com.pinapia.vana.agentruntime.RuntimeJSONValue
import kotlinx.serialization.Serializable

@Serializable
data class AskUserQuestion(
    val question: String,
    val options: List<Option>,
    val allowsMultiple: Boolean = false,
) {
    @Serializable
    data class Option(
        val label: String,
        val detail: String = "",
    )

    companion object {
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 5
        const val MAX_QUESTION = 40
        const val MAX_LABEL = 16
        const val MAX_DETAIL = 30
        const val DECLINE_TEXT = "跳过这个问题"

        fun encodeForToolMetadata(question: AskUserQuestion): RuntimeJSONValue =
            RuntimeJSONValue.ObjectValue(
                mapOf(
                    "question" to RuntimeJSONValue.StringValue(question.question),
                    "allowsMultiple" to RuntimeJSONValue.BoolValue(question.allowsMultiple),
                    "options" to RuntimeJSONValue.ArrayValue(
                        question.options.map { option ->
                            RuntimeJSONValue.ObjectValue(
                                buildMap {
                                    put("label", RuntimeJSONValue.StringValue(option.label))
                                    if (option.detail.isNotEmpty()) {
                                        put("detail", RuntimeJSONValue.StringValue(option.detail))
                                    }
                                },
                            )
                        },
                    ),
                ),
            )

        fun decode(fromToolMetadata: RuntimeJSONValue?): AskUserQuestion? {
            val metadata = fromToolMetadata ?: return null
            val question = metadata["question"]?.stringValue?.trim().orEmpty()
            if (question.isEmpty()) return null
            val options = metadata["options"]?.arrayValue.orEmpty().mapNotNull { raw ->
                val label = raw["label"]?.stringValue?.trim().orEmpty()
                if (label.isEmpty()) return@mapNotNull null
                Option(label = label, detail = raw["detail"]?.stringValue.orEmpty())
            }
            if (options.size < MIN_OPTIONS) return null
            return AskUserQuestion(
                question = question,
                options = options,
                allowsMultiple = metadata["allowsMultiple"]?.boolValue == true,
            )
        }
    }
}

@Serializable
data class AskUserAnswer(
    val choices: List<String> = emptyList(),
    val custom: String = "",
    val declined: Boolean = false,
) {
    val messageText: String
        get() {
            if (declined) return AskUserQuestion.DECLINE_TEXT
            val parts = choices.toMutableList()
            val trimmed = custom.trim()
            if (trimmed.isNotEmpty()) parts += trimmed
            return parts.joinToString("、")
        }

    val isEmpty: Boolean get() = messageText.isEmpty()

    val itemCount: Int
        get() {
            if (declined) return 0
            return choices.size + if (custom.trim().isEmpty()) 0 else 1
        }
}
