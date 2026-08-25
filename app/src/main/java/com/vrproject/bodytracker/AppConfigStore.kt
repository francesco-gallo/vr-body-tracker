package com.vrproject.bodytracker

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

data class JointOffset(
    val x: Float = 0f,
    val y: Float = 0f
)

enum class TrackerModelType(val displayName: String) {
    MEDIAPIPE_LITE("MediaPipe Lite"),
    MEDIAPIPE_FULL("MediaPipe Full"),
    MEDIAPIPE_HEAVY("MediaPipe Heavy"),
    MLKIT("MLKit Pose")
}

data class AppConfig(
    val ip: String = "192.168.1.10",
    val port: Int = 9000,
    val heightMeters: Float = 1.70f,
    val fps: Int = 60,
    val smoothing: Int = 35,
    val invertCamera: Boolean = false,
    val modelType: TrackerModelType = TrackerModelType.MEDIAPIPE_LITE,
    val cameraId: String = "",
    val globalZOffset: Float = 0f,
    val headOffset: JointOffset = JointOffset(),
    val hipOffset: JointOffset = JointOffset(),
    val chestOffset: JointOffset = JointOffset(),
    val feetOffset: JointOffset = JointOffset(),
    val kneesOffset: JointOffset = JointOffset(),
    val elbowsOffset: JointOffset = JointOffset(),

    // Tracker Toggle Flags
    val enableHead: Boolean = true,
    val enableChest: Boolean = true,
    val enableHip: Boolean = true,
    val enableFeet: Boolean = true,
    val enableKnees: Boolean = true,
    val enableElbows: Boolean = true
)

object AppConfigStore {
    private const val PREFS_NAME = "vr_body_tracker_prefs"
    private const val KEY_CONFIG = "key_app_config"
    private val gson = Gson()

    fun defaultConfig() = AppConfig()

    fun load(context: Context): AppConfig {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIG, null) ?: return defaultConfig()
        return try {
            gson.fromJson(json, AppConfig::class.java) ?: defaultConfig()
        } catch (_: Exception) {
            defaultConfig()
        }
    }

    fun save(context: Context, config: AppConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, gson.toJson(config)).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}