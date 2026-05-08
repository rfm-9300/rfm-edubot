package com.rfm.edubot.shared

import java.util.UUID

object Ids {
    fun next(): String = UUID.randomUUID().toString()
}
