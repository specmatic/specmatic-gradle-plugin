package io.specmatic.gradle.utils

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

val httpClient =
    OkHttpClient
        .Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
