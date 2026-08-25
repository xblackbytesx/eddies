package com.eddies.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
        // We map non-2xx into AppError ourselves rather than having Ktor throw.
        expectSuccess = false

        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            // No socket timeout: an idle exchange stream is normal between
            // ticks, and a socket timeout here would tear down a healthy feed.
            socketTimeoutMillis = Long.MAX_VALUE
        }

        // No Logging plugin, ever. The request URLs carry the exact list of coins
        // the user holds, which is the one thing this app exists to keep private.
    }
}
