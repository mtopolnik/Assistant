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

private const val DEMO_API_KEY = "demo"

val ARTIST_LAZY = lazy {
    Log.e("lifecycle", "lazy.value", Exception("Lazy Artist"))
    val deltas = listOf(0, 29, 11, 0, -63, 24)
    val b = StringBuilder()
    var prev = 'D'
    for (delta in deltas) {
        prev += delta
        b.append(prev)
    }
    b.toString()
}

private fun l(value: String) = lazy { value }

enum class AiVendor(
    val baseUrl: String,
    private val chatModelLazy: Lazy<AiModel>
) {
    DEMO("demo", lazy { AiModel.DEMO }),
    ANTHROPIC("https://api.anthropic.com/v1/", lazy { AiModel.CLAUDE_3_7_SONNET }),
    DEEPSEEK("https://api.deepseek.com/", lazy { AiModel.DEEPSEEK_CHAT }),
    XAI("https://api.x.ai/v1/", lazy { AiModel.GROK }),
    OPENAI("https://api.openai.com/v1/", lazy { AiModel.GPT_4O });

    val chatModel get() = chatModelLazy.value
    val apiKeyPref = name.lowercase() + "_api_key"
}

enum class AiModel(
    private val apiIdLazy: Lazy<String>,
    private val acronymLazy: Lazy<String>,
    private val nameLazy: Lazy<String>,
    val vendor: AiVendor
) {
    DEMO(l("demo"), l("Demo"), l("Demo"), AiVendor.DEMO),
    CLAUDE_3_7_SONNET(l(MODEL_ID_SONNET_3_7), l("Sonnet"), l("Claude Sonnet 3.7"), AiVendor.ANTHROPIC),
    DEEPSEEK_CHAT(l(MODEL_ID_DEEPSEEK_CHAT), l("DS Chat"), l("DeepSeek Chat"), AiVendor.DEEPSEEK),
    DEEPSEEK_REASONER(l(MODEL_ID_DEEPSEEK_REASONER), l("DS Reason"), l("DeepSeek Reasoner"), AiVendor.DEEPSEEK),
    GROK(l(MODEL_ID_GROK), l("Grok"), l("Grok 2"), AiVendor.XAI),
    GPT_4O(l(MODEL_ID_GPT_4O), l("GPT-4o"), l("GPT-4o"), AiVendor.OPENAI),
    O1(l(MODEL_ID_O1), l("o1"), l("o1"), AiVendor.OPENAI),
    O3_MINI(l(MODEL_ID_O3_MINI), l("o3-mini"), l("o3-mini"), AiVendor.OPENAI),
    GPT_4O_REALTIME(l(MODEL_ID_GPT_4O_REALTIME), l("4o RT"), l("GPT-4o Realtime"), AiVendor.OPENAI),
    GPT_4O_MINI_REALTIME(l(MODEL_ID_GPT_4O_MINI_REALTIME), l("min RT"), l("GPT-4o Mini Realtime"), AiVendor.OPENAI),
    ARTIST_3(lazy { "${ARTIST_LAZY.value.lowercase()}-3" }, ARTIST_LAZY, ARTIST_LAZY, AiVendor.OPENAI);

    val apiId: String get() = apiIdLazy.value
    val acronym: String get() = acronymLazy.value
    val fullName: String get() = nameLazy.value

    fun isChatModel() = this != ARTIST_3
    fun isAnthropicApi() = this == CLAUDE_3_7_SONNET || this == GROK

}

class OpenAiKey(val text: String) {
    fun isBlank() = text.isBlank()
    fun isNotBlank() = text.isNotBlank()
    fun isDemoKey() = text.isDemoKey()
    fun allowsGpt4() = isNotBlank() && !isDemoKey()
    fun allowsTts() = allowsGpt4() && !text.isGptOnlyKey()
    fun allowsArtist() = text.isImageGenKey()
    fun allowsRealtime() = allowsGpt4() && !text.isRtDisabledKey()

    override fun toString() = text
}

class ApiKeyWallet(prefs: SharedPreferences) {
    val supportedModels: List<AiModel>
    private val anthropicKey: String = prefs.apiKey(AiVendor.ANTHROPIC)
    private val xaiKey: String = prefs.apiKey(AiVendor.XAI)
    private val deepSeekKey: String = prefs.apiKey(AiVendor.DEEPSEEK)
    private val openaiKey: OpenAiKey = OpenAiKey(prefs.apiKey(AiVendor.OPENAI))

    init {
        supportedModels = mutableListOf<AiModel>().also { models ->
            if (isDemo()) models.add(AiModel.DEMO)
            if (hasAnthropicKey()) models.add(AiModel.CLAUDE_3_7_SONNET)
            if (hasXaiKey()) models.add(AiModel.GROK)
            if (hasDeepSeekKey()) {
                models.add(AiModel.DEEPSEEK_CHAT)
                models.add(AiModel.DEEPSEEK_REASONER)
            }
            if (openaiKey.allowsGpt4()) models.add(AiModel.GPT_4O)
            if (openaiKey.allowsRealtime()) {
//                models.add(AiModel.O1) -- doesn't support streaming response
                models.add(AiModel.O3_MINI)
                models.add(AiModel.GPT_4O_REALTIME)
                models.add(AiModel.GPT_4O_MINI_REALTIME)
            }
            if (openaiKey.allowsArtist()) models.add(AiModel.ARTIST_3)
        }
    }

    fun hasAnthropicKey() = anthropicKey.isNotBlank()
    fun hasOpenaiKey() = openaiKey.isNotBlank()
    fun hasXaiKey() = xaiKey.isNotBlank()
    fun hasDeepSeekKey() = deepSeekKey.isNotBlank()
    fun isNotEmpty() = hasXaiKey() || hasAnthropicKey() || hasOpenaiKey() || hasDeepSeekKey()
    fun isEmpty() = !isNotEmpty()
    fun allowsTts() = openaiKey.allowsTts()
    fun isDemo() = openaiKey.isDemoKey()
}

fun String.isDemoKey() = this.trim().lowercase() == DEMO_API_KEY
fun String.looksLikeApiKey() = isDemoKey() || looksLikeAnthropicKey() || looksLikeXaiKey()
        || looksLikeOpenAiKey() || looksLikeDeepSeekKey()
fun String.looksLikeAnthropicKey() = length == 108 && startsWith("sk-ant-")
fun String.looksLikeXaiKey() = length == 84 && startsWith("xai-")
fun String.looksLikeDeepSeekKey() = length == 35 && startsWith("sk-")
fun String.looksLikeOpenAiKey() = length >= 51 && startsWith("sk-")

private fun String.isGptOnlyKey() = setContainsHashMemoized(this, GPT_ONLY_KEY_HASHES, keyToIsGptOnly)
private val keyToIsGptOnly = ConcurrentHashMap<String, Boolean>()
private val GPT_ONLY_KEY_HASHES = hashSetOf(
    "DIkQ9HIwN3Ky+t53aMHyojOYAsXBFBnZQvnhbU2oyPs="
)

private fun String.isImageGenKey() = setContainsHashMemoized(this, IMAGE_KEY_HASHES, keyToIsImageGen)
private val keyToIsImageGen = ConcurrentHashMap<String, Boolean>()
private val IMAGE_KEY_HASHES = hashSetOf(
    "WlYejPDJf0ba5LefiDKy2gqb4PeXKIO36iejO7y5NuE=",
    "YPrJZ6ypdiqNcLciJBqpZ6FTAFdcLwbsb9yi7g0u0/s="
)

private fun String.isRtDisabledKey() = setContainsHashMemoized(this, RT_DISABLED_KEY_HASHES, keyToIsDisabledRt)
private val keyToIsDisabledRt = ConcurrentHashMap<String, Boolean>()
private val RT_DISABLED_KEY_HASHES = hashSetOf(
    "DIkQ9HIwN3Ky+t53aMHyojOYAsXBFBnZQvnhbU2oyPs=",
    "WlYejPDJf0ba5LefiDKy2gqb4PeXKIO36iejO7y5NuE=",
    "Ej1/kPkeX2/5AVBalQHV+Fg/5QSo9UjK+XgDWFhOQ10="
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

 fun apiKeyHash(apiKey: String): String =
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
