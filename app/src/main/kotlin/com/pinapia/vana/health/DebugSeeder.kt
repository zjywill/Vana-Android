package com.pinapia.vana.health

import android.util.Log
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Debug 种子数据写入 Health Connect。
 *
 * 写权限只在 debug Manifest 里声明。没授权时诚实回报，不假装写进去了。
 */
class DebugSeeder(private val healthStore: HealthStore) {
    data class SeedResult(
        val writesAvailable: Boolean,
        val skipped: List<String>,
    )

    suspend fun seed(): SeedResult {
        val client = healthStore.connectClientOrNull()
            ?: return SeedResult(writesAvailable = false, skipped = emptyList())
        val granted = client.permissionController.getGrantedPermissions()
        val writePerms = healthStore.writePermissions
        if (granted.none { it in writePerms }) {
            return SeedResult(writesAvailable = false, skipped = emptyList())
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val metadata = Metadata.manualEntry()
        val skipped = mutableSetOf<String>()
        var wroteAnything = false

        suspend fun insert(label: String, records: List<Record>) {
            if (records.isEmpty()) return
            try {
                client.insertRecords(records)
                wroteAnything = true
            } catch (error: SecurityException) {
                skipped += label
                Log.w(TAG, "跳过 $label：${error.message}")
            } catch (error: Throwable) {
                skipped += label
                Log.w(TAG, "跳过 $label：${error.message}")
            }
        }

        // 按类型分开写:关掉某一项不该让整批都失败。
        val stepRecords = mutableListOf<StepsRecord>()
        val restingRecords = mutableListOf<RestingHeartRateRecord>()
        val hrvRecords = mutableListOf<HeartRateVariabilityRmssdRecord>()
        val weightRecords = mutableListOf<WeightRecord>()
        val bodyFatRecords = mutableListOf<BodyFatRecord>()
        val oxygenRecords = mutableListOf<OxygenSaturationRecord>()
        val respiratoryRecords = mutableListOf<RespiratoryRateRecord>()
        val temperatureRecords = mutableListOf<BodyTemperatureRecord>()
        val bpRecords = mutableListOf<BloodPressureRecord>()
        val sleepRecords = mutableListOf<SleepSessionRecord>()
        val workoutRecords = mutableListOf<ExerciseSessionRecord>()
        val energyRecords = mutableListOf<ActiveCaloriesBurnedRecord>()
        val heartSamples = mutableListOf<HeartRateRecord>()

        for (dayOffset in 1..30) {
            val day = today.minusDays(dayOffset.toLong())
            val noon = day.atTime(12, 0).atZone(zone).toInstant()
            val steps = (4_000 + (dayOffset * 1_937) % 11_001).toLong()
            val resting = (55 + (dayOffset * 7) % 16).toLong()
            val hrv = 30.0 + (dayOffset * 13) % 51
            val weight = 70.0 - (30 - dayOffset) * 0.018 + (dayOffset % 3) * 0.08
            val bodyFat = 20.0 - (30 - dayOffset) * 0.012 + (dayOffset % 4) * 0.07

            stepRecords += StepsRecord(
                startTime = noon,
                startZoneOffset = zone.rules.getOffset(noon),
                endTime = noon.plusSeconds(60),
                endZoneOffset = zone.rules.getOffset(noon),
                count = steps,
                metadata = metadata,
            )
            restingRecords += RestingHeartRateRecord(
                time = noon,
                zoneOffset = zone.rules.getOffset(noon),
                beatsPerMinute = resting,
                metadata = metadata,
            )
            hrvRecords += HeartRateVariabilityRmssdRecord(
                time = noon,
                zoneOffset = zone.rules.getOffset(noon),
                heartRateVariabilityMillis = hrv,
                metadata = metadata,
            )
            weightRecords += WeightRecord(
                time = noon,
                zoneOffset = zone.rules.getOffset(noon),
                weight = Mass.kilograms(weight),
                metadata = metadata,
            )
            bodyFatRecords += BodyFatRecord(
                time = noon,
                zoneOffset = zone.rules.getOffset(noon),
                percentage = Percentage(bodyFat),
                metadata = metadata,
            )
            oxygenRecords += OxygenSaturationRecord(
                time = noon,
                zoneOffset = zone.rules.getOffset(noon),
                percentage = Percentage((95 + (dayOffset * 2) % 4).toDouble()),
                metadata = metadata,
            )
            respiratoryRecords += RespiratoryRateRecord(
                time = noon,
                zoneOffset = zone.rules.getOffset(noon),
                rate = (13 + (dayOffset * 3) % 5).toDouble(),
                metadata = metadata,
            )
            temperatureRecords += BodyTemperatureRecord(
                time = noon,
                zoneOffset = zone.rules.getOffset(noon),
                temperature = Temperature.celsius(36.2 + ((dayOffset * 7) % 9) / 10.0),
                metadata = metadata,
            )
            if (dayOffset % 3 == 0) {
                bpRecords += BloodPressureRecord(
                    time = noon,
                    zoneOffset = zone.rules.getOffset(noon),
                    systolic = Pressure.millimetersOfMercury((112 + (dayOffset * 5) % 17).toDouble()),
                    diastolic = Pressure.millimetersOfMercury((70 + (dayOffset * 3) % 13).toDouble()),
                    metadata = metadata,
                )
            }

            val previous = day.minusDays(1)
            val jitter = (dayOffset * 17) % 91 - 45
            val durationMinutes = 360 + (dayOffset * 29) % 121
            val bedtime = previous.atTime(23, 0).atZone(zone).plusMinutes(jitter.toLong())
            val wake = bedtime.plusMinutes(durationMinutes.toLong())
            val deepEnd = bedtime.plusMinutes((durationMinutes * 2 / 10).toLong())
            val coreEnd = bedtime.plusMinutes((durationMinutes * 7 / 10).toLong())
            sleepRecords += SleepSessionRecord(
                startTime = bedtime.toInstant(),
                startZoneOffset = bedtime.offset,
                endTime = wake.toInstant(),
                endZoneOffset = wake.offset,
                stages = listOf(
                    SleepSessionRecord.Stage(
                        startTime = bedtime.toInstant(),
                        endTime = deepEnd.toInstant(),
                        stage = SleepSessionRecord.STAGE_TYPE_DEEP,
                    ),
                    SleepSessionRecord.Stage(
                        startTime = deepEnd.toInstant(),
                        endTime = coreEnd.toInstant(),
                        stage = SleepSessionRecord.STAGE_TYPE_LIGHT,
                    ),
                    SleepSessionRecord.Stage(
                        startTime = coreEnd.toInstant(),
                        endTime = wake.toInstant(),
                        stage = SleepSessionRecord.STAGE_TYPE_REM,
                    ),
                ),
                metadata = metadata,
            )

            if (dayOffset % 3 == 0 && dayOffset in 2..29) {
                val start = day.atTime(18, (dayOffset * 7) % 30).atZone(zone)
                val minutes = 30 + (dayOffset * 11) % 31
                val end = start.plusMinutes(minutes.toLong())
                val type = if (dayOffset % 2 == 0) {
                    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                } else {
                    ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                }
                workoutRecords += ExerciseSessionRecord(
                    startTime = start.toInstant(),
                    startZoneOffset = start.offset,
                    endTime = end.toInstant(),
                    endZoneOffset = end.offset,
                    exerciseType = type,
                    title = if (type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING) "跑步" else "骑行",
                    metadata = metadata,
                )
                energyRecords += ActiveCaloriesBurnedRecord(
                    startTime = start.toInstant(),
                    startZoneOffset = start.offset,
                    endTime = end.toInstant(),
                    endZoneOffset = end.offset,
                    energy = Energy.kilocalories((220 + (dayOffset * 31) % 281).toDouble()),
                    metadata = metadata,
                )
            }
        }

        // 今天到此刻的逐小时步数。
        val now = ZonedDateTime.now(zone)
        val startOfDay = today.atStartOfDay(zone)
        for (hour in 0..now.hour) {
            val start = startOfDay.plusHours(hour.toLong())
            val end = start.plusMinutes(55)
            if (!end.isBefore(now)) continue
            val shape = when (hour) {
                8, 9 -> 1.0
                12, 13 -> 0.6
                18, 19 -> 0.9
                in 7..21 -> 0.35
                else -> 0.03
            }
            val steps = (900 * shape + (hour % 4) * 20).roundToInt().toLong()
            if (steps > 0) {
                stepRecords += StepsRecord(
                    startTime = start.toInstant(),
                    startZoneOffset = start.offset,
                    endTime = end.toInstant(),
                    endZoneOffset = end.offset,
                    count = steps,
                    metadata = metadata,
                )
            }
            heartSamples += HeartRateRecord(
                startTime = start.toInstant(),
                startZoneOffset = start.offset,
                endTime = end.toInstant(),
                endZoneOffset = end.offset,
                samples = listOf(
                    HeartRateRecord.Sample(
                        time = start.toInstant(),
                        beatsPerMinute = (58 + 26 * shape + (hour % 3)).roundToInt().toLong(),
                    ),
                ),
                metadata = metadata,
            )
        }

        insert("步数", stepRecords)
        insert("静息心率", restingRecords)
        insert("HRV", hrvRecords)
        insert("体重", weightRecords)
        insert("体脂", bodyFatRecords)
        insert("血氧", oxygenRecords)
        insert("呼吸频率", respiratoryRecords)
        insert("体温", temperatureRecords)
        insert("血压", bpRecords)
        insert("睡眠", sleepRecords)
        insert("锻炼", workoutRecords)
        insert("活动消耗", energyRecords)
        insert("心率", heartSamples)

        if (!wroteAnything) {
            return SeedResult(writesAvailable = false, skipped = skipped.sorted())
        }
        return SeedResult(writesAvailable = true, skipped = skipped.sorted())
    }

    suspend fun selfCheck() {
        val tools = HealthTools(healthStore)
        // 通过 registry 跑一遍太重;直接调 digest + 各查询。
        Log.i(TAG, "=== digest ===\n${tools.digestForSuggestions()}")
        val names = listOf(
            "daily_steps", "sleep_summary", "heart_rate_summary", "workouts",
            "body_metrics", "blood_pressure", "vitals",
        )
        for (name in names) {
            Log.i(TAG, "=== $name ===")
            try {
                val text = healthStore.withOwnerAccess {
                    when (name) {
                        "daily_steps" -> dailyActivity(7).joinToString { "${it.date}:${it.steps}" }
                        "sleep_summary" -> sleepSummary(7).joinToString { "${it.night}:${it.asleepSeconds}" }
                        "heart_rate_summary" -> heartRateSummary(7).joinToString { "${it.date}:${it.restingHR}" }
                        "workouts" -> workouts(7).joinToString { "${it.date}:${it.typeName}" }
                        "body_metrics" -> bodyMetrics(7).joinToString { "${it.date}:${it.weightKg}" }
                        "blood_pressure" -> bloodPressureSummary(7).joinToString { "${it.date}:${it.systolic}" }
                        "vitals" -> vitalsSummary(7).joinToString { "${it.date}:${it.oxygen}" }
                        else -> ""
                    }
                }
                Log.i(TAG, text.ifBlank { "（空）" })
            } catch (error: Throwable) {
                Log.e(TAG, "失败：$error")
            }
        }
    }

    companion object {
        private const val TAG = "VanaDebug"
    }
}
