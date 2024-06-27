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

import android.content.SharedPreferences
import android.util.Base64
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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

    fun isChatModel() = this != ARTIST_3
}

class OpenAiKey(val text: String) {
    fun isEmpty() = text.isBlank()
    fun allowsGpt3() = text.isNotBlank()
    fun allowsGpt4() = text.isNotBlank() && !text.isGpt3OnlyKey()
    fun allowsTts() = text.isNotBlank() && !text.isGpt3OnlyKey() && !text.isGptOnlyKey()
    fun allowsArtist() = text.isImageGenKey()

    override fun toString() = text
}

class ApiKeyWallet(prefs: SharedPreferences) {
    val supportedModels: List<AiModel>
    private val anthropicKey: String
    private val openaiKey: OpenAiKey

    init {
        anthropicKey = prefs.anthropicApiKey
        openaiKey = prefs.openaiApiKey
        supportedModels = mutableListOf<AiModel>().also { models ->
            if (openaiKey.allowsGpt3()) models.add(AiModel.GPT_3)
            if (openaiKey.allowsGpt4()) models.add(AiModel.GPT_4)
            if (anthropicKey.isNotBlank()) models.add(AiModel.CLAUDE_3_5_SONNET)
            if (openaiKey.allowsArtist()) models.add(AiModel.ARTIST_3)
        }
        Log.i("key", "supportedModels $supportedModels")
    }

    fun isEmpty() = anthropicKey == "" && openaiKey.isEmpty()
    fun allowsTts() = openaiKey.allowsTts()
}

fun String.looksLikeApiKey() = looksLikeAnthropicKey() || looksLikeOpenAiKey()
fun String.looksLikeAnthropicKey() = length == 108 && startsWith("sk-ant-api")
fun String.looksLikeOpenAiKey() = length == 51 && startsWith("sk-")

private fun String.isGpt3OnlyKey() = setContainsHashMemoized(this, GPT3_ONLY_KEY_HASHES, keyToIsGpt3Only)
private val keyToIsGpt3Only = ConcurrentHashMap<String, Boolean>()
private val GPT3_ONLY_KEY_HASHES = hashSetOf(
    "DIkQ9HIwN3Ky+t53aMHyojOYAsXBFBnZQvnhbU2oyPs=",
)

private fun String.isGptOnlyKey() = setContainsHashMemoized(this, GPT_ONLY_KEY_HASHES, keyToIsGptOnly)
private val keyToIsGptOnly = ConcurrentHashMap<String, Boolean>()
private val GPT_ONLY_KEY_HASHES = hashSetOf(
    "Ej1/kPkeX2/5AVBalQHV+Fg/5QSo9UjK+XgDWFhOQ10="
)

private fun String.isImageGenKey() = setContainsHashMemoized(this, IMAGE_KEY_HASHES, keyToIsImageGen)
private val keyToIsImageGen = ConcurrentHashMap<String, Boolean>()
private val IMAGE_KEY_HASHES = hashSetOf(
    "WlYejPDJf0ba5LefiDKy2gqb4PeXKIO36iejO7y5NuE=",
)

private fun setContainsHashMemoized(key: String, set: Set<String>, cache: MutableMap<String, Boolean>): Boolean {
    cache[key]?.also {
        return it
    }
    val hash = apiKeyHash(key)
    return set.contains(hash).also {
        cache[key] = it
    }
}

private fun apiKeyHash(apiKey: String): String =
    apiKey.toByteArray(Charsets.UTF_8).let {
        MessageDigest.getInstance("SHA-256").digest(it)
    }.let {
        Base64.encodeToString(it, Base64.NO_WRAP)
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

fun resetClients() {
    resetOpenAi()
    resetAnthropic()
}

val jsonCodec = Json {
    isLenient = true
    ignoreUnknownKeys = true
}
