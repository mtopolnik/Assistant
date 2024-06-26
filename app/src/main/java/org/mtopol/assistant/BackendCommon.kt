/*
 * Copyright (c) 2024 Marko Topolnik.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.mtopol.assistant

import android.util.Log
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.ANDROID
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val ARTIST_LAZY = lazy {
    Log.e("lifecycle", "lazy.value", Exception("diagnostic exception"))
    val deltas = listOf(0, 29, 11, 0, -63, 24)
    val b = StringBuilder()
    var prev = 'D'
    for (delta in deltas) {
        prev += delta
        b.append(prev)
    }
    b.toString()
}

enum class AiModel(
    private val apiIdLazy: Lazy<String>,
    private val uiIdLazy: Lazy<String>
) {
    GPT_3(lazy { MODEL_ID_GPT_3 }, lazy { "GPT-3.5" }),
    GPT_4(lazy { MODEL_ID_GPT_4 }, lazy { "GPT-4o" }),
    CLAUDE_3_5_SONNET(lazy { MODEL_ID_SONNET_3_5 }, lazy { "Sonnet" }),
    ARTIST_3(lazy { "${ARTIST_LAZY.value.lowercase()}-3" }, ARTIST_LAZY);

    val apiId get() = apiIdLazy.value
    val uiId get() = uiIdLazy.value
}

val chatModels = listOf(AiModel.GPT_3, AiModel.GPT_4, AiModel.CLAUDE_3_5_SONNET)

fun AiModel.isChatModel() = this in chatModels
fun AiModel.isImageModel() = !isChatModel()

fun resetClients() {
    resetOpenAi()
    resetAnthropic()
}

fun HttpClientConfig<OkHttpConfig>.commonApiClientSetup() {
    install(HttpTimeout) {
        connectTimeoutMillis = 4000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 180_000
    }
    install(HttpRequestRetry) {
        retryIf { _, response -> response.status.value == 429 } // == Too Many Requests
        maxRetries = 2
        exponentialDelay(2.0, 2000)
    }
    install(ContentNegotiation) {
        json(jsonCodec)
    }
    install(ContentEncoding)
    install(Logging) {
        level = LogLevel.HEADERS
        logger = Logger.ANDROID
    }
    expectSuccess = true
}

val jsonCodec = Json {
    isLenient = true
    ignoreUnknownKeys = true
}
