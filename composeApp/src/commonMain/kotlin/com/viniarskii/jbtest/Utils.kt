package com.viniarskii.jbtest

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
suspend fun debugLog(message: String) = coroutineScope {
    println("${Clock.System.now()} - ${coroutineContext[CoroutineName]}: $message")
}
