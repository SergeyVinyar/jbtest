package com.viniarskii.jbtest

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform