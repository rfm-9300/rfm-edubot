package com.rfm.edubot.persistence

import com.rfm.edubot.config.AppConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The host's backup and restore scripts must agree with the app on which database holds what. */
class MongoModuleLayoutTest {
    private val script = File("scripts/mongo-databases.sh").readText()

    private fun array(name: String): List<String> {
        val body = Regex("""(?m)^$name=\(([^)]*)\)""").find(script)?.groupValues?.get(1)
            ?: error("scripts/mongo-databases.sh has no $name=(...)")
        return body.trim().split(Regex("\\s+"))
    }

    private fun default(name: String): String =
        Regex("""(?m)^$name="\$\{$name:-(.*)}"\s*$""").find(script)?.groupValues?.get(1)
            ?: error("scripts/mongo-databases.sh has no $name default")

    @Test
    fun `the scripts move the same collections as the app`() {
        assertEquals(MongoModule.PLATFORM_COLLECTIONS, array("PLATFORM_COLLECTIONS"))
        assertEquals(MongoModule.RUNTIME_COLLECTIONS, array("RUNTIME_COLLECTIONS"))
    }

    @Test
    fun `the scripts name the databases as the app does`() {
        val config = AppConfig.MongoConfig(uri = "unused")
        assertEquals(config.database, default("DATA_DB"))
        assertEquals(config.platformDatabase, default("PLATFORM_DB").replace("\${DATA_DB}", config.database))
        assertEquals(config.runtimeDatabase, default("RUNTIME_DB").replace("\${DATA_DB}", config.database))
    }
}
