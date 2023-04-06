package org.mtopol.assistant

import android.content.Context
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success

object OpenAI {
    private const val API_URL = "https://api.openai.com/v1/engines/davinci-codex/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Throws(IOException::class)
    suspend fun getResponse(context: Context, prompt: String): String {
        val body = """
            {
                "prompt": "$prompt",
                "temperature": 0.5,
                "max_tokens": 100,
                "top_p": 1,
                "frequency_penalty": 0,
                "presence_penalty": 0
            }
        """.trimIndent().toRequestBody(JSON)
        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer ${context.getString(R.string.openai_api_key)}")
            .post(body)
            .build()

        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(
                        if (response.isSuccessful) {
                            success(response.body?.string() ?: "")
                        } else {
                            failure(IOException("HTTP response code: ${response.code}"))
                        }
                    )
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(failure(e))
                }
            })
        }
    }
}
