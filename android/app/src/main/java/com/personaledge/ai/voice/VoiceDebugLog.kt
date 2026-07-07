package com.personaledge.ai.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** QA debug logs → host ingest (emulator: 10.0.2.2 = dev machine). */
object VoiceDebugLog {
    private const val TAG = "VoiceQA"
    private const val SESSION = "34665d"
    private const val ENDPOINT = "http://10.0.2.2:7491/ingest/c1531e88-03f0-4d2d-899a-1df3cd84590d"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cacheFile: File? = null

    fun init(context: Context) {
        cacheFile = File(context.cacheDir, "debug-34665d.log")
    }

    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val payload = JSONObject().apply {
            put("sessionId", SESSION)
            put("hypothesisId", hypothesisId)
            put("location", location)
            put("message", message)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject(data))
        }
        Log.i(TAG, "[$hypothesisId] $message $data")
        scope.launch {
            try {
                cacheFile?.appendText(payload.toString() + "\n")
            } catch (_: Exception) {
            }
            try {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Debug-Session-Id", SESSION)
                    doOutput = true
                    connectTimeout = 2_000
                    readTimeout = 2_000
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.inputStream?.close()
                conn.disconnect()
            } catch (_: Exception) {
                // Host ingest optional; logcat still has VoiceQA tag
            }
        }
    }
}
