package com.vrproject.bodytracker

import android.content.Context
import androidx.core.content.edit

enum class TrackerModelType(val key: String, val displayName: String) {
    MEDIAPIPE_LITE("mediapipe_lite", "MediaPipe (Lite)"),
    MEDIAPIPE_FULL("mediapipe_full", "MediaPipe (Full)"),
    MEDIAPIPE_HEAVY("mediapipe_heavy", "MediaPipe (Heavy)"),
    MLKIT("mlkit", "Google ML Kit");

    companion object {
        fun fromKey(key: String): TrackerModelType {
            return values().firstOrNull { it.key == key } ?: MEDIAPIPE_LITE
        }
    }
}

data class JointOffset(
    val x: Float = 0f,
    val y: Float = 0f
)

data class AppConfig(
    val ip: String,
    val port: Int,
    val heightMeters: Float,
    val fps: Int,
    val smoothing: Int,
    val invertCamera: Boolean = false,
    val modelType: TrackerModelType = TrackerModelType.MEDIAPIPE_LITE,
    val cameraId: String = "",
    val headOffset: JointOffset = JointOffset(),
    val hipOffset: JointOffset = JointOffset(),
    val chestOffset: JointOffset = JointOffset(),
    val feetOffset: JointOffset = JointOffset(),
    val kneesOffset: JointOffset = JointOffset(),
    val elbowsOffset: JointOffset = JointOffset()
)

object AppConfigStore {
    private const val PREFS_NAME = "body_osc_config"
    private const val PREF_KEY = "latest_config"

    fun defaultConfig(): AppConfig = AppConfig(
        ip = "192.168.1.10",
        port = 9000,
        heightMeters = 1.70f,
        fps = 60,
        smoothing = 35,
        invertCamera = false,
        modelType = TrackerModelType.MEDIAPIPE_LITE,
        cameraId = ""
    )

    fun load(context: Context): AppConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, null) ?: return defaultConfig()
        return try {
            fromMap(parseRawString(raw))
        } catch (_: Exception) {
            clear(context)
            defaultConfig()
        }
    }

    fun save(context: Context, config: AppConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(PREF_KEY, encodeMap(toMap(config)))
        }
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove(PREF_KEY) }
    }

    fun toMap(config: AppConfig): Map<String, String> = mapOf(
        "ip" to config.ip,
        "port" to config.port.toString(),
        "heightMeters" to config.heightMeters.toString(),
        "fps" to config.fps.toString(),
        "smoothing" to config.smoothing.toString(),
        "invertCamera" to config.invertCamera.toString(),
        "modelType" to config.modelType.key,
        "cameraId" to config.cameraId,
        "headX" to config.headOffset.x.toString(),
        "headY" to config.headOffset.y.toString(),
        "hipX" to config.hipOffset.x.toString(),
        "hipY" to config.hipOffset.y.toString(),
        "chestX" to config.chestOffset.x.toString(),
        "chestY" to config.chestOffset.y.toString(),
        "feetX" to config.feetOffset.x.toString(),
        "feetY" to config.feetOffset.y.toString(),
        "kneesX" to config.kneesOffset.x.toString(),
        "kneesY" to config.kneesOffset.y.toString(),
        "elbowsX" to config.elbowsOffset.x.toString(),
        "elbowsY" to config.elbowsOffset.y.toString()
    )

    fun fromMap(values: Map<String, String>): AppConfig {
        val defaults = defaultConfig()
        return AppConfig(
            ip = values["ip"] ?: defaults.ip,
            port = values["port"]?.toIntOrNull() ?: defaults.port,
            heightMeters = values["heightMeters"]?.toFloatOrNull() ?: defaults.heightMeters,
            fps = values["fps"]?.toIntOrNull() ?: defaults.fps,
            smoothing = values["smoothing"]?.toIntOrNull() ?: defaults.smoothing,
            invertCamera = values["invertCamera"]?.toBooleanStrictOrNull() ?: defaults.invertCamera,
            modelType = TrackerModelType.fromKey(values["modelType"] ?: defaults.modelType.key),
            cameraId = values["cameraId"] ?: defaults.cameraId,
            headOffset = JointOffset(values["headX"]?.toFloatOrNull() ?: 0f, values["headY"]?.toFloatOrNull() ?: 0f),
            hipOffset = JointOffset(values["hipX"]?.toFloatOrNull() ?: 0f, values["hipY"]?.toFloatOrNull() ?: 0f),
            chestOffset = JointOffset(values["chestX"]?.toFloatOrNull() ?: 0f, values["chestY"]?.toFloatOrNull() ?: 0f),
            feetOffset = JointOffset(values["feetX"]?.toFloatOrNull() ?: 0f, values["feetY"]?.toFloatOrNull() ?: 0f),
            kneesOffset = JointOffset(values["kneesX"]?.toFloatOrNull() ?: 0f, values["kneesY"]?.toFloatOrNull() ?: 0f),
            elbowsOffset = JointOffset(values["elbowsX"]?.toFloatOrNull() ?: 0f, values["elbowsY"]?.toFloatOrNull() ?: 0f)
        )
    }

    private fun encodeMap(values: Map<String, String>): String =
        values.entries.joinToString(";") { (key, value) -> "$key=$value" }

    private fun parseRawString(raw: String): Map<String, String> =
        raw.split(";")
            .filter { it.contains("=") }
            .associate { entry ->
                val parts = entry.split("=", limit = 2)
                parts[0] to parts.getOrElse(1) { "" }
            }
}