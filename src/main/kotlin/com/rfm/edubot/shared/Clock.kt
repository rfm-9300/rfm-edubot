package com.rfm.edubot.shared

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

object SystemClock {
    fun now(): Instant = Clock.System.now()
}
