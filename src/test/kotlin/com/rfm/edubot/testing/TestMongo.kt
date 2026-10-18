package com.rfm.edubot.testing

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import org.bson.types.ObjectId
import org.testcontainers.containers.MongoDBContainer

/**
 * MongoDB for repository tests: `TEST_MONGO_URI` when set (a local mongod), otherwise one MongoDB
 * container shared by every test class in the run. Each call gets its own fresh databases.
 */
object TestMongo {
    private val uri: String by lazy {
        System.getenv("TEST_MONGO_URI")?.takeIf { it.isNotBlank() }
            ?: MongoDBContainer("mongo:7").also { it.start() }.replicaSetUrl
    }

    /** Fresh database names, for a test that writes before [MongoModule.initialize] runs. */
    fun config(name: String): AppConfig.MongoConfig =
        AppConfig.MongoConfig(uri = uri, database = "${name}_${ObjectId().toHexString().takeLast(8)}")

    fun module(name: String): MongoModule = MongoModule(config(name)).also { it.initialize() }
}
