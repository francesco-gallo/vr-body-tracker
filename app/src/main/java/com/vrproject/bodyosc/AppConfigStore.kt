package com.vrproject.bodyosc

import android.content.Context
import android.content.SharedPreferences

data class BodyPartSelection(
    val head: Boolean = true,
    val torso: Boolean = true,
    val leftArm: Boolean = true,
    val rightArm: Boolean = true,
    val leftLeg: Boolean = true,
    val rightLeg: Boolean = true
)

data class AppConfig(
    val ip: String,
    val port: Int,
    val prefix: String,
    val vrchatTrackers: Boolean,
    val heightMeters: Float,
    val frontCamera: Boolean,
    val fps: Int,
    val smoothing: Int,
    val bundle: Boolean,
    val invertX: Boolean,
    val invertY: Boolean,
    val invertZ: Boolean,
    val bodyParts: BodyPartSelection = BodyPartSelection()
)

object AppConfigStore {
    private const val PREFS_NAME = "body_osc_config"
    private const val PREF_KEY = "latest_config"

    fun defaultConfig(): AppConfig = AppConfig(
        ip = "192.168.1.10",
        port = 9000,
        prefix = "/tracking/pose",
        vrchatTrackers = true,
        heightMeters = 1.70f,
        frontCamera = false,
        fps = 20,
        smoothing = 35,
        bundle = true,
        invertX = false,
        invertY = false,
        invertZ = false,
        bodyParts = BodyPartSelection()
    )

    fun load(context: Context): AppConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY, null) ?: return defaultConfig()
        return fromMap(parseRawString(raw))
    }

    fun save(context: Context, config: AppConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_KEY, encodeMap(toMap(config)))
            .apply()
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_KEY).apply()
    }

    fun toMap(config: AppConfig): Map<String, String> = mapOf(
        "ip" to config.ip,
        "port" to config.port.toString(),
        "prefix" to config.prefix,
        "vrchatTrackers" to config.vrchatTrackers.toString(),
        "heightMeters" to config.heightMeters.toString(),
        "frontCamera" to config.frontCamera.toString(),
        "fps" to config.fps.toString(),
        "smoothing" to config.smoothing.toString(),
        "bundle" to config.bundle.toString(),
        "invertX" to config.invertX.toString(),
        "invertY" to config.invertY.toString(),
        "invertZ" to config.invertZ.toString(),
        "head" to config.bodyParts.head.toString(),
        "torso" to config.bodyParts.torso.toString(),
        "leftArm" to config.bodyParts.leftArm.toString(),
        "rightArm" to config.bodyParts.rightArm.toString(),
        "leftLeg" to config.bodyParts.leftLeg.toString(),
        "rightLeg" to config.bodyParts.rightLeg.toString()
    )

    fun fromMap(values: Map<String, String>): AppConfig {
        val defaults = defaultConfig()
        return AppConfig(
            ip = values["ip"] ?: defaults.ip,
            port = values["port"]?.toIntOrNull() ?: defaults.port,
            prefix = values["prefix"] ?: defaults.prefix,
            vrchatTrackers = values["vrchatTrackers"]?.toBooleanStrictOrNull() ?: defaults.vrchatTrackers,
            heightMeters = values["heightMeters"]?.toFloatOrNull() ?: defaults.heightMeters,
            frontCamera = values["frontCamera"]?.toBooleanStrictOrNull() ?: defaults.frontCamera,
            fps = values["fps"]?.toIntOrNull() ?: defaults.fps,
            smoothing = values["smoothing"]?.toIntOrNull() ?: defaults.smoothing,
            bundle = values["bundle"]?.toBooleanStrictOrNull() ?: defaults.bundle,
            invertX = values["invertX"]?.toBooleanStrictOrNull() ?: defaults.invertX,
            invertY = values["invertY"]?.toBooleanStrictOrNull() ?: defaults.invertY,
            invertZ = values["invertZ"]?.toBooleanStrictOrNull() ?: defaults.invertZ,
            bodyParts = BodyPartSelection(
                head = values["head"]?.toBooleanStrictOrNull() ?: defaults.bodyParts.head,
                torso = values["torso"]?.toBooleanStrictOrNull() ?: defaults.bodyParts.torso,
                leftArm = values["leftArm"]?.toBooleanStrictOrNull() ?: defaults.bodyParts.leftArm,
                rightArm = values["rightArm"]?.toBooleanStrictOrNull() ?: defaults.bodyParts.rightArm,
                leftLeg = values["leftLeg"]?.toBooleanStrictOrNull() ?: defaults.bodyParts.leftLeg,
                rightLeg = values["rightLeg"]?.toBooleanStrictOrNull() ?: defaults.bodyParts.rightLeg
            )
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
