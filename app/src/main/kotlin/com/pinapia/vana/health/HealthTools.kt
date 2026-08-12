package com.pinapia.vana.health

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class HealthTools(private val healthStore: HealthStore) {
    fun registry(): CapabilityRegistry {
        val definitions = specs.map { it.definition }
        return CapabilityRegistry(definitions = definitions) { invocation ->
            val spec = specs.firstOrNull { it.name == invocation.name }
            if (spec == null) {
                return@CapabilityRegistry CapabilityExecutionResult(
                    output = AgentToolOutput(
                        kind = AgentToolOutput.Kind.TEXT,
                        text = "不支持名为 ${invocation.name} 的健康工具。",
                    ),
                    isError = true,
                )
            }
            try {
                val report = spec.run(daysFrom(invocation.input), activityFrom(invocation.input))
                CapabilityExecutionResult(
                    output = AgentToolOutput(
                        kind = AgentToolOutput.Kind.TABLE,
                        text = report.modelText,
                    ),
                )
            } catch (error: Throwable) {
                CapabilityExecutionResult(
                    output = AgentToolOutput(
                        kind = AgentToolOutput.Kind.TEXT,
                        text = "健康数据查询失败：${error.message ?: error::class.java.simpleName}",
                    ),
                    isError = true,
                )
            }
        }
    }

    /** 给 QuestionSuggester 看的最近 7 天摘要。 */
    suspend fun digestForSuggestions(): String {
        val wanted = listOf("sleep_summary", "daily_steps", "workouts")
        val lines = mutableListOf<String>()
        for (name in wanted) {
            val spec = specs.firstOrNull { it.name == name } ?: continue
            val text = runCatching { spec.run(7, null).modelText }.getOrNull() ?: continue
            lines += "[$name]\n$text"
        }
        return if (lines.isEmpty()) "（暂时没有可用数据）" else lines.joinToString("\n\n")
    }

    private data class Spec(
        val name: String,
        val description: String,
        val supportsActivityFilter: Boolean = false,
        val run: suspend (days: Int, activity: String?) -> HealthReport,
    ) {
        val definition: CapabilityDefinition
            get() {
                val properties = mutableMapOf(
                    "days" to RuntimeJSONValue.ObjectValue(
                        mapOf(
                            "type" to RuntimeJSONValue.StringValue("integer"),
                            "description" to RuntimeJSONValue.StringValue("查询最近多少天，范围 1–90，默认 7"),
                            "minimum" to RuntimeJSONValue.IntValue(1),
                            "maximum" to RuntimeJSONValue.IntValue(90),
                        ),
                    ),
                )
                if (supportsActivityFilter) {
                    properties["activity"] = RuntimeJSONValue.ObjectValue(
                        mapOf(
                            "type" to RuntimeJSONValue.StringValue("string"),
                            "description" to RuntimeJSONValue.StringValue("只看某一类锻炼时传，留空表示全部"),
                            "enum" to RuntimeJSONValue.ArrayValue(ACTIVITY_NAMES.map { RuntimeJSONValue.StringValue(it) }),
                        ),
                    )
                }
                return CapabilityDefinition(
                    name = name,
                    description = description,
                    inputSchema = RuntimeJSONValue.ObjectValue(
                        mapOf(
                            "type" to RuntimeJSONValue.StringValue("object"),
                            "properties" to RuntimeJSONValue.ObjectValue(properties),
                            "required" to RuntimeJSONValue.ArrayValue(listOf(RuntimeJSONValue.StringValue("days"))),
                            "additionalProperties" to RuntimeJSONValue.BoolValue(false),
                        ),
                    ),
                    strictPreferred = false,
                )
            }
    }

    private val specs = listOf(
        Spec(
            name = "daily_steps",
            description = "当用户问及步数、活动量或久坐情况时调用。" +
                "返回最近若干天的每日步数和活动消耗。",
        ) { days, _ ->
            val dayCount = normalizedDays(days)
            val window = healthStore.dailyActivity(dayCount)
            val history = healthStore.dailySteps(HealthStore.BASELINE_DAYS)
            renderActivity(window, dayCount, Baseline.of(history.map { it.value }))
        },
        Spec(
            name = "sleep_summary",
            description = "当用户问及睡眠时长、入睡起床时间、睡眠分期（深睡/核心/REM）、" +
                "夜间醒来、睡眠效率或睡眠期间心率时调用。返回最近若干晚的逐晚数据。",
        ) { days, _ ->
            val dayCount = normalizedDays(days)
            val window = healthStore.sleepSummary(dayCount)
            val history = healthStore.sleepSummary(HealthStore.BASELINE_DAYS)
            renderSleep(window, dayCount, Baseline.of(history.map { it.asleepSeconds }))
        },
        Spec(
            name = "heart_rate_summary",
            description = "当用户问及静息心率、HRV、全天心率高低、恢复或压力趋势时调用。" +
                "返回最近若干天的静息心率、心率变异性,以及每天心率的最低/最高/平均。",
        ) { days, _ ->
            val dayCount = normalizedDays(days)
            val window = healthStore.heartRateSummary(dayCount)
            val history = healthStore.heartRateSummary(HealthStore.BASELINE_DAYS)
            renderHeart(
                window,
                dayCount,
                restingBaseline = Baseline.of(history.mapNotNull { it.restingHR }),
                hrvBaseline = Baseline.of(history.mapNotNull { it.hrv }),
            )
        },
        Spec(
            name = "workouts",
            description = "当用户问及锻炼、运动频率、运动时长、距离或活动消耗时调用。" +
                "返回最近若干天的锻炼记录。只关心某一种运动时传 activity。",
            supportsActivityFilter = true,
        ) { days, activity ->
            val dayCount = normalizedDays(days)
            renderWorkouts(healthStore.workouts(dayCount, activity), dayCount, activity)
        },
        Spec(
            name = "body_metrics",
            description = "当用户问及体重、体脂或身体指标变化时调用。返回最近若干天有记录的体重和体脂趋势。",
        ) { days, _ ->
            val dayCount = normalizedDays(days)
            renderBody(healthStore.bodyMetrics(dayCount), dayCount)
        },
        Spec(
            name = "blood_pressure",
            description = "当用户问及血压、收缩压或舒张压时调用。返回最近若干天有记录的每日血压均值。" +
                "多数人没有这项数据（需要血压计或第三方 app 写入），没有记录时会明确说明。",
        ) { days, _ ->
            val dayCount = normalizedDays(days)
            renderBloodPressure(healthStore.bloodPressureSummary(dayCount), dayCount)
        },
        Spec(
            name = "vitals",
            description = "当用户问及血氧、呼吸频率或体温时调用。返回最近若干天有记录的每日均值。" +
                "这些项需要支持的设备，多数人只有部分数据，没有记录时会明确说明。",
        ) { days, _ ->
            val dayCount = normalizedDays(days)
            renderVitals(healthStore.vitalsSummary(dayCount), dayCount)
        },
        Spec(
            name = "correlations",
            description = "当用户问「什么影响了我的睡眠/心率」「某件事有没有用」「有什么规律」时调用。" +
                "把最近的数据按条件分成两组做对比，返回每组天数和差值。" +
                "days 建议传 60 以上，样本太少不会有结果。",
        ) { days, _ ->
            val dayCount = maxOf(normalizedDays(days), 30)
            val steps = healthStore.dailySteps(dayCount)
            val nights = healthStore.sleepSummary(dayCount)
            val hearts = healthStore.heartRateSummary(dayCount)
            val sessions = healthStore.workouts(dayCount)
            renderComparisons(
                HealthAnalysis.comparisons(steps, nights, hearts, sessions),
                dayCount,
            )
        },
        Spec(
            name = "health_records",
            description = "当用户问及化验单、体检报告、血糖血脂等医院检查结果时调用。",
        ) { days, _ ->
            HealthReport.empty(
                title = "最近 ${maxOf(days, 365)} 天化验与体检记录",
                note = "Health Connect 里没有医院同步的化验记录。国内多数机构尚未接入；可以拍一张化验单给我。",
            )
        },
    )

    private fun renderActivity(
        values: List<DayActivity>,
        days: Int,
        baseline: Baseline?,
    ): HealthReport {
        val recorded = values.filter { it.steps > 0 }
        if (recorded.isEmpty()) {
            return HealthReport.empty(
                title = "最近 $days 天活动量",
                note = "没有步数记录。${healthStore.emptyDataHint()}",
            )
        }
        val hasEnergy = values.any { it.activeEnergyKcal != null }
        val report = HealthReport(title = "最近 $days 天活动量")
        report.columns = listOf("日期", "步数") + if (hasEnergy) listOf("消耗 kcal") else emptyList()
        report.rows = values.map { item ->
            val row = mutableListOf(healthStore.formatSteps(item.steps))
            if (hasEnergy) row += healthStore.formatInteger(item.activeEnergyKcal)
            HealthReport.Row(healthStore.formatDate(item.date), row)
        }
        val dailyAverage = values.map { it.steps }.average()
        report.summary = mutableListOf("日均 ${healthStore.formatSteps(dailyAverage)} 步")
        HealthAnalysis.baselineLine(baseline, dailyAverage) { "${healthStore.formatSteps(it)} 步" }
            ?.let { report.summary += it }
        return report
    }

    private fun renderSleep(
        values: List<NightSleep>,
        days: Int,
        baseline: Baseline?,
    ): HealthReport {
        if (values.isEmpty()) {
            return HealthReport.empty(
                title = "最近 $days 晚睡眠",
                note = "没有睡眠记录。${healthStore.emptyDataHint()}",
            )
        }
        val hasStages = values.any { it.deepSeconds > 0 || it.coreSeconds > 0 || it.remSeconds > 0 }
        val title = if (values.size == days) {
            "最近 $days 晚睡眠"
        } else {
            "最近 $days 天睡眠（${values.size} 晚有记录）"
        }
        val report = HealthReport(title = title)
        report.columns = buildList {
            addAll(listOf("日期", "睡着", "入睡–起床"))
            if (hasStages) addAll(listOf("深睡 分", "核心 分", "REM 分"))
            addAll(listOf("清醒 分", "醒来 次"))
        }
        report.rows = values.map { item ->
            val bedtime = item.bedtime?.atZone(java.time.ZoneId.systemDefault())
                ?.toLocalTime()?.toString()?.take(5) ?: "--:--"
            val wake = item.wake?.atZone(java.time.ZoneId.systemDefault())
                ?.toLocalTime()?.toString()?.take(5) ?: "--:--"
            val row = mutableListOf(
                healthStore.formatDuration(item.asleepSeconds),
                "$bedtime–$wake",
            )
            if (hasStages) {
                row += healthStore.formatMinutes(item.deepSeconds)
                row += healthStore.formatMinutes(item.coreSeconds)
                row += healthStore.formatMinutes(item.remSeconds)
            }
            row += healthStore.formatMinutes(item.awakeSeconds)
            row += item.wakeCount.toString()
            HealthReport.Row(healthStore.formatDate(item.night), row)
        }
        val averageAsleep = values.map { it.asleepSeconds }.average()
        report.summary = mutableListOf("平均睡着 ${healthStore.formatDuration(averageAsleep)}")
        if (!hasStages) {
            report.notes += "这些记录没有分期数据，别按深睡不足解读。"
        }
        HealthAnalysis.baselineLine(baseline, averageAsleep, healthStore::formatDuration)
            ?.let { report.summary += it }
        values.lastOrNull()?.let { last ->
            stalenessNote(last.night, "睡眠记录")?.let { report.notes += it }
        }
        return report
    }

    private fun renderHeart(
        values: List<DayHeart>,
        days: Int,
        restingBaseline: Baseline?,
        hrvBaseline: Baseline?,
    ): HealthReport {
        if (values.isEmpty()) {
            return HealthReport.empty(
                title = "最近 $days 天心率",
                note = "没有心率记录。${healthStore.emptyDataHint()}",
            )
        }
        val report = HealthReport(title = "最近 $days 天心率")
        report.columns = listOf("日期", "静息", "HRV ms", "最低", "最高", "平均")
        report.rows = values.map { item ->
            HealthReport.Row(
                healthStore.formatDate(item.date),
                listOf(
                    healthStore.formatInteger(item.restingHR),
                    healthStore.formatInteger(item.hrv),
                    healthStore.formatInteger(item.lowestHR),
                    healthStore.formatInteger(item.highestHR),
                    healthStore.formatInteger(item.averageHR),
                ),
            )
        }
        val restingValues = values.mapNotNull { it.restingHR }
        val hrvValues = values.mapNotNull { it.hrv }
        if (restingValues.isNotEmpty()) {
            report.summary += "静息心率区间 ${restingValues.min().roundToInt()}–${restingValues.max().roundToInt()} 次/分"
            HealthAnalysis.baselineLine(restingBaseline, restingValues.average()) {
                "${it.roundToInt()} 次/分"
            }?.let { report.summary += "静息心率 $it" }
        }
        if (hrvValues.isNotEmpty()) {
            report.summary += "HRV 区间 ${hrvValues.min().roundToInt()}–${hrvValues.max().roundToInt()} ms"
            HealthAnalysis.baselineLine(hrvBaseline, hrvValues.average()) {
                "${it.roundToInt()} ms"
            }?.let { report.summary += "HRV $it" }
        }
        return report
    }

    private fun renderWorkouts(values: List<WorkoutItem>, days: Int, activity: String?): HealthReport {
        val label = activity ?: "锻炼"
        if (values.isEmpty()) {
            val note = if (activity != null) {
                "没有${activity}记录（其他类型的锻炼可能有，不带筛选再查一次即可）。"
            } else {
                "没有锻炼记录。${healthStore.emptyDataHint()}"
            }
            return HealthReport.empty(title = "最近 $days 天$label", note = note)
        }
        val report = HealthReport(title = "最近 $days 天$label")
        report.columns = listOf("日期", "类型", "时长")
        report.rows = values.map {
            HealthReport.Row(
                healthStore.formatDate(it.date),
                listOf(it.typeName, healthStore.formatDuration(it.durationSeconds)),
            )
        }
        report.summary = listOf(
            "共 ${values.size} 次，合计 ${healthStore.formatDuration(values.sumOf { it.durationSeconds })}",
        )
        return report
    }

    private fun renderBody(values: List<DayBody>, days: Int): HealthReport {
        val recorded = values.filter { it.weightKg != null || it.bodyFatPercent != null }
        if (recorded.isEmpty()) {
            return HealthReport.empty(
                title = "最近 $days 天体重与体脂",
                note = "没有体重或体脂记录。${healthStore.emptyDataHint()}",
            )
        }
        val report = HealthReport(title = "最近 $days 天体重与体脂")
        report.columns = listOf("日期", "体重 kg", "体脂 %")
        report.rows = recorded.map {
            HealthReport.Row(
                healthStore.formatDate(it.date),
                listOf(
                    healthStore.formatDecimal(it.weightKg),
                    healthStore.formatDecimal(it.bodyFatPercent),
                ),
            )
        }
        val weights = recorded.mapNotNull { it.weightKg }
        if (weights.size >= 2) {
            report.summary += "体重变化 ${healthStore.formatSigned(weights.last() - weights.first(), " kg")}"
        }
        val fats = recorded.mapNotNull { it.bodyFatPercent }
        if (fats.size >= 2) {
            report.summary += "体脂变化 ${healthStore.formatSigned(fats.last() - fats.first(), " 个百分点")}"
        }
        return report
    }

    private fun renderBloodPressure(values: List<DayBloodPressure>, days: Int): HealthReport {
        val recorded = values.filter { it.systolic != null || it.diastolic != null }
        if (recorded.isEmpty()) {
            return HealthReport.empty(
                title = "最近 $days 天血压",
                note = "没有血压记录。血压需要血压计或第三方 app 写入 Health Connect，" +
                    "手机/手表通常不测血压。如果确认记过，请在 Health Connect 里确认已允许 Vana 读取。",
            )
        }
        val report = HealthReport(title = "最近 $days 天血压（每日均值，mmHg）")
        report.columns = listOf("日期", "收缩压", "舒张压")
        report.rows = recorded.map {
            HealthReport.Row(
                healthStore.formatDate(it.date),
                listOf(
                    healthStore.formatInteger(it.systolic),
                    healthStore.formatInteger(it.diastolic),
                ),
            )
        }
        val sys = recorded.mapNotNull { it.systolic }.average().takeIf { !it.isNaN() }
        val dia = recorded.mapNotNull { it.diastolic }.average().takeIf { !it.isNaN() }
        if (sys != null && dia != null) {
            report.summary += "均值 ${sys.roundToInt()}/${dia.roundToInt()} mmHg，共 ${recorded.size} 天有记录"
        }
        return report
    }

    private fun renderVitals(values: List<DayVitals>, days: Int): HealthReport {
        val recorded = values.filter {
            it.oxygen != null || it.respiratoryRate != null || it.bodyTemperature != null
        }
        if (recorded.isEmpty()) {
            return HealthReport.empty(
                title = "最近 $days 天体征",
                note = "没有血氧、呼吸频率或体温记录。血氧和呼吸频率需要支持的手表并开启对应功能，" +
                    "体温需要体温计写入。手腕睡眠温度在 Health Connect 里没有稳定对等物，这里不硬凑。" +
                    "如果确认记过，请在 Health Connect 里确认已允许 Vana 读取。",
            )
        }
        val hasOxygen = recorded.any { it.oxygen != null }
        val hasBreathing = recorded.any { it.respiratoryRate != null }
        val hasTemperature = recorded.any { it.bodyTemperature != null }
        val report = HealthReport(title = "最近 $days 天体征（每日均值）")
        report.columns = buildList {
            add("日期")
            if (hasOxygen) add("血氧 %")
            if (hasBreathing) add("呼吸 次/分")
            if (hasTemperature) add("体温 ℃")
        }
        report.rows = recorded.map { item ->
            val row = mutableListOf<String>()
            if (hasOxygen) row += healthStore.formatDecimal(item.oxygen)
            if (hasBreathing) row += healthStore.formatDecimal(item.respiratoryRate)
            if (hasTemperature) row += healthStore.formatDecimal(item.bodyTemperature)
            HealthReport.Row(healthStore.formatDate(item.date), row)
        }
        recorded.mapNotNull { it.oxygen }.average().takeIf { !it.isNaN() }?.let {
            report.summary += "血氧均值 ${healthStore.formatDecimal(it)}%"
        }
        recorded.mapNotNull { it.respiratoryRate }.average().takeIf { !it.isNaN() }?.let {
            report.summary += "呼吸频率均值 ${healthStore.formatDecimal(it)} 次/分"
        }
        return report
    }

    private fun renderComparisons(comparisons: List<Comparison>, days: Int): HealthReport {
        if (comparisons.isEmpty()) {
            return HealthReport.empty(
                title = "最近 $days 天的分组对比",
                note = "数据还不足以做对比（每组至少要有 3 天）。多记录一段时间再看。",
            )
        }
        val report = HealthReport(title = "最近 $days 天的分组对比")
        report.columns = listOf("对比项", "满足条件", "其余", "差值", "天数")
        report.rows = comparisons.map { item ->
            HealthReport.Row(
                item.label,
                listOf(
                    healthStore.formatDecimal(item.withCondition),
                    healthStore.formatDecimal(item.withoutCondition),
                    healthStore.formatSigned(item.difference, ""),
                    "${item.withCount} / ${item.withoutCount}",
                ),
            )
        }
        report.notes = listOf(
            "单位跟随各项：睡眠为分钟，心率为次/分，HRV 为 ms。",
            "这是相关不是因果，样本量也小，只能当线索。",
        )
        return report
    }

    private fun stalenessNote(latest: LocalDate, unit: String): String? {
        val days = ChronoUnit.DAYS.between(latest, LocalDate.now()).toInt()
        if (days <= 1) return null
        return "最新的一条是 ${healthStore.formatDate(latest)}，距今 $days 天，这之后没有$unit——不要当成昨天的数据。"
    }

    companion object {
        val ACTIVITY_NAMES = listOf("跑步", "骑行", "步行", "力量训练", "游泳", "徒步", "瑜伽", "高强度间歇训练")

        val TOOL_NAMES = listOf(
            "daily_steps",
            "sleep_summary",
            "heart_rate_summary",
            "workouts",
            "body_metrics",
            "blood_pressure",
            "vitals",
            "correlations",
            "health_records",
        )

        fun normalizedDays(days: Int): Int = days.coerceIn(1, 90)

        fun daysFrom(input: String): Int {
            val days = runCatching { RuntimeJSONValue.decode(from = input)["days"]?.intValue }.getOrNull() ?: 7
            return normalizedDays(days)
        }

        fun activityFrom(input: String): String? {
            val value = runCatching { RuntimeJSONValue.decode(from = input)["activity"]?.stringValue }.getOrNull()
            return value?.takeIf { it in ACTIVITY_NAMES }
        }

        fun label(forName: String, activity: String? = null): String = when (forName) {
            "daily_steps" -> "活动量"
            "sleep_summary" -> "睡眠"
            "heart_rate_summary" -> "静息心率与 HRV"
            "workouts" -> activity ?: "锻炼"
            "body_metrics" -> "体重与体脂"
            "blood_pressure" -> "血压"
            "vitals" -> "血氧、呼吸与体温"
            "correlations" -> "数据之间的关联"
            "health_records" -> "化验与体检记录"
            else -> "健康数据"
        }
    }
}
