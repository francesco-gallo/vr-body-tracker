package com.vrproject.bodytracker

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigStoreTest {
    @Test
    fun `config round trips through a map`() {
        val config = AppConfig(
            ip = "10.0.0.25",
            port = 9001,
            prefix = "/tracking/custom",
            vrchatTrackers = false,
            heightMeters = 1.85f,
            frontCamera = true,
            fps = 30,
            smoothing = 66,
            bundle = false,
            invertX = true,
            invertY = false,
            invertZ = true,
            bodyParts = BodyPartSelection(
                head = false,
                torso = true,
                leftArm = false,
                rightArm = true,
                leftLeg = false,
                rightLeg = true
            )
        )

        val map = AppConfigStore.toMap(config)
        val restored = AppConfigStore.fromMap(map)

        assertEquals(config, restored)
    }

    @Test
    fun `default config values are stable`() {
        val config = AppConfigStore.defaultConfig()

        assertEquals("192.168.1.10", config.ip)
        assertEquals(9000, config.port)
        assertEquals("/tracking/pose", config.prefix)
        assertEquals(true, config.vrchatTrackers)
        assertEquals(1.70f, config.heightMeters)
        assertEquals(true, config.bodyParts.head)
        assertEquals(true, config.bodyParts.torso)
    }
}
