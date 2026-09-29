package com.pinapia.vana.tenant

import com.pinapia.vana.ui.L10n
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
                CHILD -> L10n.text("儿童", "Child")
                TEEN -> L10n.text("青少年", "Teen")
                ADULT -> L10n.text("成年人", "Adult")
                SENIOR -> L10n.text("老年人", "Older adult")
            }
    }

    val isOwner: Boolean get() = kind == Kind.OWNER

    val displayName: String
        get() {
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) return trimmed
            return if (isOwner) L10n.text(OWNER_DEFAULT_NAME, "Me") else L10n.text("家人", "Family member")
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
