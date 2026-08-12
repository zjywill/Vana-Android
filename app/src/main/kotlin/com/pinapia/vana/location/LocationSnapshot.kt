package com.pinapia.vana.location

data class LocationSnapshot(
    val place: String? = null,
) {
    val isKnown: Boolean get() = !place.isNullOrBlank()

    fun instructionBlock(canSearchWeb: Boolean = false): String? {
        val place = place?.takeIf { it.isNotBlank() } ?: return null
        var text = "他此刻大概在：$place（设备粗定位，只精确到城市，可能有偏差）。" +
            "只有季节气候、时差、当地饮食或者就医方式真的影响到答案时才用它，其余时候不要提起。" +
            "不要据此推断他的具体住址、单位或行程，他没说的地点信息一律不要替他补。"
        if (canSearchWeb) {
            text += "要上网搜本地信息时，查询词里最多写到城市，不要连着他的身体数值一起搜。"
        }
        return text
    }

    companion object {
        val unknown = LocationSnapshot(place = null)

        fun describe(
            cityWithContext: String? = null,
            cityName: String? = null,
            regionName: String? = null,
        ): String? {
            trimmed(cityWithContext)?.let { return it }
            val parts = mutableListOf<String>()
            for (part in listOf(trimmed(cityName), trimmed(regionName))) {
                if (part == null) continue
                if (parts.lastOrNull() == part) continue
                parts += part
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("，")
        }

        private fun trimmed(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }
    }
}
