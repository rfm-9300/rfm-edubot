package com.rfm.edubot.testing

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.persistence.MongoModule
import org.bson.types.ObjectId
import org.testcontainers.containers.MongoDBContainer

/**
 * MongoDB for repository tests: `TEST_MONGO_URI` when set (a local mongod), otherwise one MongoDB
 * container shared by every test class in the run. Each call gets its own fresh database.
 */
object TestMongo {
    private val uri: String by lazy {
        System.getenv("TEST_MONGO_URI")?.takeIf { it.isNotBlank() }
            ?: MongoDBContainer("mongo:7").also { it.start() }.replicaSetUrl
    }

    fun module(name: String): MongoModule =
        MongoModule(AppConfig.MongoConfig(uri = uri, database = "${name}_${ObjectId().toHexString().takeLast(8)}")).also { it.initialize() }
}
