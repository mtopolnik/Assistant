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

enum class AiVendor(
    val baseUrl: String,
    private val chatModelLazy: Lazy<AiModel>
) {
    DEMO("demo", lazy { AiModel.DEMO }),
    ANTHROPIC("https://api.anthropic.com/v1/", lazy { AiModel.CLAUDE_4_SONNET }),
    DEEPSEEK("https://api.deepseek.com/", lazy { AiModel.DEEPSEEK_CHAT }),
    XAI("https://api.x.ai/v1/", lazy { AiModel.GROK }),
    OPENAI("https://api.openai.com/v1/", lazy { AiModel.GPT_5 });

    val chatModel get() = chatModelLazy.value
    val apiKeyPref = name.lowercase() + "_api_key"
}

enum class AiModel(
    val apiId: String,
    val acronym: String,
    val fullName: String,
    val vendor: AiVendor
) {
    DEMO("demo", "Demo", "Demo", AiVendor.DEMO),
    CLAUDE_4_SONNET(MODEL_ID_SONNET_4_6, "Sonnet", "Claude Sonnet 4.6", AiVendor.ANTHROPIC),
    CLAUDE_4_SONNET_THINKING(MODEL_ID_SONNET_4_6, "SonnetThink", "Claude Sonnet 4.6 Thinking", AiVendor.ANTHROPIC),
    DEEPSEEK_CHAT(MODEL_ID_DEEPSEEK_CHAT, "DS Chat", "DeepSeek Chat", AiVendor.DEEPSEEK),
    DEEPSEEK_REASONER(MODEL_ID_DEEPSEEK_REASONER, "DS Reason", "DeepSeek Reasoner", AiVendor.DEEPSEEK),
    GROK(MODEL_ID_GROK_41_FAST_NON_REASONING, "Grok", "Grok 4.1", AiVendor.XAI),
    GROK_REASONING(MODEL_ID_GROK_41_FAST, "Grok Think", "Grok 4.1 Reasoning", AiVendor.XAI),
    GROK_IMAGINE(MODEL_ID_GROK_IMAGINE, "Grok Img", "Grok Imagine", AiVendor.XAI),
    GPT_5(MODEL_ID_GPT_52, "GPT-5.2", "GPT-5.2", AiVendor.OPENAI),
    GPT_5_MINI(MODEL_ID_GPT_5_MINI, "GPT-5-min", "GPT-5-mini", AiVendor.OPENAI),
    GPT_REALTIME(MODEL_ID_GPT_REALTIME, "GPT RT", "GPT Realtime", AiVendor.OPENAI),
    GPT_REALTIME_MINI(MODEL_ID_GPT_REALTIME_MINI, "RT min", "GPT Realtime Mini", AiVendor.OPENAI),
    GPT_IMAGE_15(MODEL_ID_GPT_IMAGE_15, "GPT Image", "GPT Image 1.5", AiVendor.OPENAI);

    fun isImageModel() = this == GPT_IMAGE_15 || this == GROK_IMAGINE
}

class OpenAiKey(val text: String) {
    fun isBlank() = text.isBlank()
    fun isNotBlank() = text.isNotBlank()
    fun isDemoKey() = text.isDemoKey()
    fun allowsGpt4() = isNotBlank() && !isDemoKey()
    fun allowsTts() = allowsGpt4() && !text.isGptOnlyKey()
    fun allowsImageGen() = text.isImageGenKey()
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
            if (hasAnthropicKey()) {
                models.add(AiModel.CLAUDE_4_SONNET)
                models.add(AiModel.CLAUDE_4_SONNET_THINKING)
            }
            if (hasXaiKey()) {
                models.add(AiModel.GROK)
                models.add(AiModel.GROK_REASONING)
                models.add(AiModel.GROK_IMAGINE)
            }
            if (hasDeepSeekKey()) {
                models.add(AiModel.DEEPSEEK_CHAT)
                models.add(AiModel.DEEPSEEK_REASONER)
            }
            if (openaiKey.allowsGpt4()) {
                models.add(AiModel.GPT_5)
                models.add(AiModel.GPT_5_MINI)
            }
            if (openaiKey.allowsRealtime()) {
                models.add(AiModel.GPT_REALTIME)
                models.add(AiModel.GPT_REALTIME_MINI)
            }
            if (openaiKey.allowsImageGen()) models.add(AiModel.GPT_IMAGE_15)
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
        socketTimeoutMillis = 90_000
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
