package com.pinapia.vana.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pinapia.vana.Features
import com.pinapia.vana.tenant.TenantScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class DayValue(val date: LocalDate, val value: Double)

data class NightSleep(
    val night: LocalDate,
    val asleepSeconds: Double,
    val bedtime: Instant? = null,
    val wake: Instant? = null,
    val deepSeconds: Double = 0.0,
    val coreSeconds: Double = 0.0,
    val remSeconds: Double = 0.0,
    val awakeSeconds: Double = 0.0,
    val wakeCount: Int = 0,
)

data class DayHeart(
    val date: LocalDate,
    val restingHR: Double? = null,
    val hrv: Double? = null,
    val lowestHR: Double? = null,
    val highestHR: Double? = null,
    val averageHR: Double? = null,
)

data class WorkoutItem(
    val date: LocalDate,
    val startTime: Instant,
    val typeName: String,
    val durationSeconds: Double,
    val activeEnergyKcal: Double? = null,
    val distanceKm: Double? = null,
    val averageHeartRate: Double? = null,
) {
    val endTime: Instant get() = startTime.plusSeconds(durationSeconds.toLong().coerceAtLeast(0))
}

data class DayActivity(
    val date: LocalDate,
    val steps: Double,
    val activeEnergyKcal: Double? = null,
    val exerciseMinutes: Double? = null,
)

data class DayBody(
    val date: LocalDate,
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
)

data class DayBloodPressure(
    val date: LocalDate,
    val systolic: Double? = null,
    val diastolic: Double? = null,
)

data class DayVitals(
    val date: LocalDate,
    val oxygen: Double? = null,
    val respiratoryRate: Double? = null,
    val bodyTemperature: Double? = null,
)

/**
 * Health Connect 读取层,只读不写。工具只返回按天聚合值。
 *
 * 这台设备上的健康数据只有机主一个人的。
 */
class HealthStore(private val context: Context) {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd")

    /**
     * API 34 以下 Health Connect 是独立 APK。三种状态要分清空态文案和按钮:
     * AVAILABLE / UPDATE_REQUIRED（去商店装或更新）/ UNAVAILABLE（系统太旧,没法集成）。
     *
     * `Features.HEALTH_CONNECT == false` 时一律当 UNAVAILABLE——大陆机普遍没有 HC,
     * 不走安装引导,也不请求权限。见 CLAUDE.md「Health Connect 暂缓」。
     */
    enum class SdkStatus {
        AVAILABLE,
        UPDATE_REQUIRED,
        UNAVAILABLE,
    }

    fun sdkStatus(): SdkStatus {
        if (!Features.HEALTH_CONNECT) return SdkStatus.UNAVAILABLE
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> SdkStatus.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SdkStatus.UPDATE_REQUIRED
            else -> SdkStatus.UNAVAILABLE
        }
    }

    private val client: HealthConnectClient?
        get() {
            if (!Features.HEALTH_CONNECT) return null
            if (sdkStatus() != SdkStatus.AVAILABLE) return null
            return runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        }

    /** 打开 Play 商店装 / 更新 Health Connect。API 34+ 系统自带,一般走不到这儿。 */
    fun openProviderInstall(): Boolean {
        val pkg = PROVIDER_PACKAGE
        val market = Intent(Intent.ACTION_VIEW).apply {
            setPackage("com.android.vending")
            data = Uri.parse("market://details?id=$pkg&url=healthconnect%3A%2F%2Fonboarding")
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(market)
            true
        } catch (_: ActivityNotFoundException) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(web) }.isSuccess
        }
    }

    fun openHealthConnectSettings(): Boolean {
        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun emptyDataHint(): String {
        if (!Features.HEALTH_CONNECT) {
            return "本机健康数据暂未接入（大陆机普遍没有 Health Connect）。可以拍照化验单，或直接聊症状与习惯。"
        }
        return when (sdkStatus()) {
            SdkStatus.UNAVAILABLE ->
                "这台设备的系统版本过旧，装不了 Health Connect，健康数据读不到。"
            SdkStatus.UPDATE_REQUIRED ->
                "还没安装（或需要更新）Health Connect。装好后再回来授权；" +
                    "另外还需要一个会往里面写数据的应用（如 Google Fit、三星健康、小米运动）。"
            SdkStatus.AVAILABLE -> EMPTY_AUTH_HINT
        }
    }

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
    )

    /** Debug 种子写入用。正式 Manifest 不声明 WRITE,这里只在 debug 构建真正能要到。 */
    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(RespiratoryRateRecord::class),
        HealthPermission.getWritePermission(BodyTemperatureRecord::class),
    )

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    fun writePermissionContract() = PermissionController.createRequestPermissionResultContract()

    fun connectClientOrNull(): HealthConnectClient? = client

    enum class ReadAccess {
        READY,
        NOT_REQUESTED,
        UNAVAILABLE,
    }

    suspend fun hasAllPermissions(): Boolean {
        val c = client ?: return false
        return c.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    /** SpokenBrief / 快捷方式用:读不到时要分清原因。 */
    suspend fun readAccess(): ReadAccess {
        val c = client ?: return ReadAccess.UNAVAILABLE
        val granted = runCatching { c.permissionController.getGrantedPermissions() }.getOrNull()
            ?: return ReadAccess.UNAVAILABLE
        if (granted.containsAll(permissions)) return ReadAccess.READY
        if (granted.isEmpty()) return ReadAccess.NOT_REQUESTED
        // 给了部分权限也算能读——工具会各自空着。
        return if (granted.any { it in permissions }) ReadAccess.READY else ReadAccess.NOT_REQUESTED
    }

    private fun assertOwner() {
        // 后台 check-in 走 withOwnerAccess,不要求当前选中机主(同 iOS HealthStore.owner)。
        if ((ownerAccessDepth.get() ?: 0) > 0) return
        check(TenantScope.isOwnerActive) {
            "当前是家人成员（${TenantScope.current.displayName}），这台设备的健康数据不属于他。"
        }
    }

    /**
     * 读机主健康数据,不要求当前选中机主。check-in / 后台派生用。
     */
    suspend fun <T> withOwnerAccess(block: suspend HealthStore.() -> T): T {
        ownerAccessDepth.set((ownerAccessDepth.get() ?: 0) + 1)
        return try {
            block()
        } finally {
            ownerAccessDepth.set((ownerAccessDepth.get() ?: 1) - 1)
        }
    }

    private fun rangeForDays(days: Int): Pair<LocalDate, TimeRangeFilter> {
        val end = LocalDate.now(zone).plusDays(1)
        val start = end.minusDays(days.toLong())
        val range = TimeRangeFilter.between(
            start.atStartOfDay(zone).toInstant(),
            end.atStartOfDay(zone).toInstant(),
        )
        return end to range
    }

    suspend fun dailyActivity(days: Int): List<DayActivity> {
        assertOwner()
        val c = client ?: return emptyList()
        val (end, range) = rangeForDays(days)
        val stepsByDay = mutableMapOf<LocalDate, Double>()
        val energyByDay = mutableMapOf<LocalDate, Double>()

        c.readRecords(ReadRecordsRequest(StepsRecord::class, timeRangeFilter = range)).records.forEach { record ->
            val day = record.startTime.atZone(zone).toLocalDate()
            stepsByDay[day] = (stepsByDay[day] ?: 0.0) + record.count
        }
        c.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, timeRangeFilter = range)).records.forEach { record ->
            val day = record.startTime.atZone(zone).toLocalDate()
            energyByDay[day] = (energyByDay[day] ?: 0.0) + record.energy.inKilocalories
        }

        return (0 until days).map { offset ->
            val day = end.minusDays((offset + 1).toLong())
            DayActivity(
                date = day,
                steps = stepsByDay[day] ?: 0.0,
                activeEnergyKcal = energyByDay[day],
            )
        }.sortedBy { it.date }
    }

    suspend fun dailySteps(days: Int): List<DayValue> =
        dailyActivity(days).map { DayValue(it.date, it.steps) }

    suspend fun sleepSummary(days: Int): List<NightSleep> {
        assertOwner()
        val c = client ?: return emptyList()
        val end = LocalDate.now(zone).plusDays(1)
        val start = end.minusDays(days.toLong() + 1)
        val range = TimeRangeFilter.between(start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant())
        val sessions = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = range)).records
        val byNight = linkedMapOf<LocalDate, MutableList<SleepSessionRecord>>()
        for (session in sessions) {
            val night = session.endTime.atZone(zone).toLocalDate().minusDays(1)
            byNight.getOrPut(night) { mutableListOf() }.add(session)
        }
        return byNight.entries
            .sortedBy { it.key }
            .takeLast(days)
            .map { (night, list) ->
                var asleep = 0.0
                var deep = 0.0
                var light = 0.0
                var rem = 0.0
                var awake = 0.0
                var wakes = 0
                val bedtime = list.minOfOrNull { it.startTime }
                val wake = list.maxOfOrNull { it.endTime }
                for (session in list) {
                    for (stage in session.stages) {
                        val seconds = ChronoUnit.SECONDS.between(stage.startTime, stage.endTime).toDouble()
                        when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP -> {
                                deep += seconds
                                asleep += seconds
                            }
                            SleepSessionRecord.STAGE_TYPE_LIGHT -> {
                                light += seconds
                                asleep += seconds
                            }
                            SleepSessionRecord.STAGE_TYPE_REM -> {
                                rem += seconds
                                asleep += seconds
                            }
                            SleepSessionRecord.STAGE_TYPE_AWAKE,
                            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
                            -> {
                                awake += seconds
                                wakes += 1
                            }
                            SleepSessionRecord.STAGE_TYPE_SLEEPING,
                            SleepSessionRecord.STAGE_TYPE_UNKNOWN,
                            -> asleep += seconds
                        }
                    }
                    if (session.stages.isEmpty()) {
                        asleep += ChronoUnit.SECONDS.between(session.startTime, session.endTime).toDouble()
                    }
                }
                NightSleep(
                    night = night,
                    asleepSeconds = asleep,
                    bedtime = bedtime,
                    wake = wake,
                    deepSeconds = deep,
                    coreSeconds = light,
                    remSeconds = rem,
                    awakeSeconds = awake,
                    wakeCount = wakes,
                )
            }
    }

    suspend fun heartRateSummary(days: Int): List<DayHeart> {
        assertOwner()
        val c = client ?: return emptyList()
        val (end, range) = rangeForDays(days)

        val resting = mutableMapOf<LocalDate, MutableList<Double>>()
        c.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, timeRangeFilter = range)).records.forEach {
            val day = it.time.atZone(zone).toLocalDate()
            resting.getOrPut(day) { mutableListOf() }.add(it.beatsPerMinute.toDouble())
        }
        val hrv = mutableMapOf<LocalDate, MutableList<Double>>()
        c.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, timeRangeFilter = range)).records.forEach {
            val day = it.time.atZone(zone).toLocalDate()
            hrv.getOrPut(day) { mutableListOf() }.add(it.heartRateVariabilityMillis)
        }
        val samples = mutableMapOf<LocalDate, MutableList<Long>>()
        c.readRecords(ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = range)).records.forEach { record ->
            for (sample in record.samples) {
                val day = sample.time.atZone(zone).toLocalDate()
                samples.getOrPut(day) { mutableListOf() }.add(sample.beatsPerMinute)
            }
        }

        return (0 until days).map { offset ->
            val day = end.minusDays((offset + 1).toLong())
            val daySamples = samples[day]
            DayHeart(
                date = day,
                restingHR = resting[day]?.average(),
                hrv = hrv[day]?.average(),
                lowestHR = daySamples?.minOrNull()?.toDouble(),
                highestHR = daySamples?.maxOrNull()?.toDouble(),
                averageHR = daySamples?.average(),
            )
        }.sortedBy { it.date }.filter {
            it.restingHR != null || it.hrv != null || it.averageHR != null
        }
    }

    suspend fun workouts(days: Int, activity: String? = null): List<WorkoutItem> {
        assertOwner()
        val c = client ?: return emptyList()
        val (_, range) = rangeForDays(days)
        return c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = range)).records
            .map { record ->
                WorkoutItem(
                    date = record.startTime.atZone(zone).toLocalDate(),
                    startTime = record.startTime,
                    typeName = exerciseName(record.exerciseType),
                    durationSeconds = ChronoUnit.SECONDS.between(record.startTime, record.endTime).toDouble(),
                )
            }
            .filter { activity == null || it.typeName == activity }
            .sortedBy { it.startTime }
    }

    suspend fun bodyMetrics(days: Int): List<DayBody> {
        assertOwner()
        val c = client ?: return emptyList()
        val (_, range) = rangeForDays(days)
        val weights = c.readRecords(ReadRecordsRequest(WeightRecord::class, timeRangeFilter = range)).records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .mapValues { (_, records) -> records.last().weight.inKilograms }
        val bodyFat = runCatching {
            c.readRecords(ReadRecordsRequest(BodyFatRecord::class, timeRangeFilter = range)).records
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .mapValues { (_, records) -> records.last().percentage.value }
        }.getOrDefault(emptyMap())

        val daysWithData = (weights.keys + bodyFat.keys).toSortedSet()
        return daysWithData.map { day ->
            DayBody(date = day, weightKg = weights[day], bodyFatPercent = bodyFat[day])
        }
    }

    /**
     * 血压。多数人没有这项数据(需要血压计或第三方 app 写入 Health Connect)。
     * 没授权 / 没数据都返回空列表,由渲染层写清楚原因,不当成查询失败。
     */
    suspend fun bloodPressureSummary(days: Int): List<DayBloodPressure> {
        assertOwner()
        val c = client ?: return emptyList()
        val (_, range) = rangeForDays(days)
        return runCatching {
            c.readRecords(ReadRecordsRequest(BloodPressureRecord::class, timeRangeFilter = range)).records
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .map { (day, records) ->
                    DayBloodPressure(
                        date = day,
                        systolic = records.map { it.systolic.inMillimetersOfMercury }.average(),
                        diastolic = records.map { it.diastolic.inMillimetersOfMercury }.average(),
                    )
                }
                .sortedBy { it.date }
        }.getOrDefault(emptyList())
    }

    /**
     * 血氧、呼吸频率、体温。多数人只有部分数据;手腕睡眠温度在 HC 里没有对等物,这里不硬凑。
     */
    suspend fun vitalsSummary(days: Int): List<DayVitals> {
        assertOwner()
        val c = client ?: return emptyList()
        val (end, range) = rangeForDays(days)

        val oxygen = runCatching {
            c.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, timeRangeFilter = range)).records
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .mapValues { (_, records) -> records.map { it.percentage.value }.average() }
        }.getOrDefault(emptyMap())

        val breathing = runCatching {
            c.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class, timeRangeFilter = range)).records
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .mapValues { (_, records) -> records.map { it.rate }.average() }
        }.getOrDefault(emptyMap())

        val temperature = runCatching {
            c.readRecords(ReadRecordsRequest(BodyTemperatureRecord::class, timeRangeFilter = range)).records
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .mapValues { (_, records) -> records.map { it.temperature.inCelsius }.average() }
        }.getOrDefault(emptyMap())

        return (0 until days).mapNotNull { offset ->
            val day = end.minusDays((offset + 1).toLong())
            val item = DayVitals(
                date = day,
                oxygen = oxygen[day],
                respiratoryRate = breathing[day],
                bodyTemperature = temperature[day],
            )
            if (item.oxygen != null || item.respiratoryRate != null || item.bodyTemperature != null) {
                item
            } else {
                null
            }
        }.sortedBy { it.date }
    }

    fun formatDate(date: LocalDate): String = date.format(dateFormatter)

    fun formatDuration(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).roundToInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0 -> "$totalMinutes 分钟"
            minutes == 0 -> "$hours 小时"
            else -> "$hours 小时 $minutes 分"
        }
    }

    fun formatSteps(value: Double): String = value.roundToInt().toString()

    fun formatInteger(value: Double?): String =
        value?.roundToInt()?.toString() ?: HealthReport.MISSING

    fun formatDecimal(value: Double?): String =
        value?.let { String.format("%.1f", it) } ?: HealthReport.MISSING

    fun formatMinutes(seconds: Double): String =
        if (seconds <= 0) HealthReport.MISSING else (seconds / 60.0).roundToInt().toString()

    fun formatSigned(value: Double, suffix: String): String {
        val sign = if (value > 0) "+" else ""
        return "$sign${String.format("%.1f", value)}$suffix"
    }

    companion object {
        const val BASELINE_DAYS = 60

        /** androidx 里同名字段是 internal,这里自己写死官方包名。 */
        private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        private val ownerAccessDepth = ThreadLocal.withInitial { 0 }

        val EMPTY_AUTH_HINT =
            "请检查 Health Connect 授权，并确认已安装会往 Health Connect 写入数据的应用（如 Google Fit、三星健康、小米运动等）。"

        private fun exerciseName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
            -> "跑步"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            -> "骑行"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "步行"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            -> "力量训练"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            -> "游泳"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "徒步"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "瑜伽"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "高强度间歇训练"
            else -> "其他"
        }
    }
}
