package com.pinapia.vana.tenant

import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Tenant(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val kind: Kind,
    var ageBand: AgeBand? = null,
    val createdAt: Instant = Clock.System.now(),
) {
    @Serializable
    enum class Kind {
        @SerialName("owner") OWNER,
        @SerialName("managed") MANAGED,
    }

    @Serializable
    enum class AgeBand {
        @SerialName("child") CHILD,
        @SerialName("teen") TEEN,
        @SerialName("adult") ADULT,
        @SerialName("senior") SENIOR,
        ;

        val label: String
            get() = when (this) {
                CHILD -> "儿童"
                TEEN -> "青少年"
                ADULT -> "成年人"
                SENIOR -> "老年人"
            }
    }

    val isOwner: Boolean get() = kind == Kind.OWNER

    val displayName: String
        get() {
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) return trimmed
            return if (isOwner) OWNER_DEFAULT_NAME else "家人"
        }

    /**
     * 进 system 段的身份块。机主返回 null——整份提示词本来就是照着「用户本人」写的。
     */
    val instructionBlock: String?
        get() {
            if (isOwner) return null
            var block = """
                关于这次对话的对象：
                - 你现在处理的是用户家人「$displayName」的健康情况，**不是用户本人的**。说到身体状况时指的都是$displayName，不要和用户自己的数据混为一谈。
                - 你没有读取${displayName}设备数据的工具。他的情况只来自这条对话里说过的话、用药与补剂清单，以及用户拍给你的化验单、报告或说明书。
                - 需要具体数值时，请用户拍一张化验单或报告发给你，不要凭印象猜，也不要说「我看到数据显示……」这种话——你没有他的数据。
            """.trimIndent()
            ageBand?.let {
                block += "\n- ${displayName}是${it.label}。参考范围、风险判断和注意事项都按这个年龄段来说；具体用药和剂量仍然交给医生。"
            }
            return block
        }

    companion object {
        const val OWNER_DEFAULT_NAME = "我自己"
        const val MAX_NAME_LENGTH = 12

        fun owner(
            id: String = UUID.randomUUID().toString(),
            name: String = OWNER_DEFAULT_NAME,
        ): Tenant = Tenant(id = id, name = name, kind = Kind.OWNER)

        fun normalized(name: String): String =
            name.trim().take(MAX_NAME_LENGTH)
    }
}
