package com.personaledge.ai.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android system TTS for x86 emulators. Sherpa/Piper audio often does not reach PC speakers;
 * the platform TTS engine routes reliably through the emulator host audio path.
 */
class EmulatorSystemTts(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var activeUtteranceId: String? = null

    @Volatile
    private var stopRequested = false

    suspend fun init(): Boolean = suspendCancellableCoroutine { cont ->
        runOnMain {
            tts?.shutdown()
            tts = TextToSpeech(appContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "System TTS init failed: status=$status")
                    if (cont.isActive) cont.resume(false)
                    return@TextToSpeech
                }
                val engine = tts ?: run {
                    if (cont.isActive) cont.resume(false)
                    return@TextToSpeech
                }
                engine.language = Locale.US
                selectNaturalVoice(engine)
                engine.setSpeechRate(1.0f)
                engine.setPitch(1.0f)
                Log.i(TAG, "System TTS ready (voice=${engine.voice?.name})")
                if (cont.isActive) cont.resume(true)
            }
        }
    }

    suspend fun speak(text: String): Boolean = suspendCancellableCoroutine { cont ->
        runOnMain {
            val engine = tts
            if (engine == null) {
                if (cont.isActive) cont.resume(false)
                return@runOnMain
            }
            stopRequested = false
            val utteranceId = "coffee-${System.currentTimeMillis()}"
            activeUtteranceId = utteranceId
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null
                    if (cont.isActive) cont.resume(!stopRequested)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null
                    if (cont.isActive) cont.resume(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null
                    if (cont.isActive) cont.resume(false)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId != activeUtteranceId) return
                    activeUtteranceId = null
                    if (cont.isActive) cont.resume(false)
                }
            })
            val params = Bundle()
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.ERROR && cont.isActive) {
                activeUtteranceId = null
                cont.resume(false)
            }
            cont.invokeOnCancellation {
                runOnMain {
                    stopRequested = true
                    engine.stop()
                    activeUtteranceId = null
                }
            }
        }
    }

    fun stop() {
        runOnMain {
            stopRequested = true
            tts?.stop()
            activeUtteranceId = null
        }
    }

    fun shutdown() {
        runOnMain {
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }

    /**
     * The default TTS voice is often a low-quality "compact" voice that sounds robotic.
     * Pick the highest-quality English voice available, preferring en-US and offline voices.
     */
    private fun selectNaturalVoice(engine: TextToSpeech) {
        try {
            val voices: Set<Voice> = engine.voices ?: return
            val candidates = voices.filter { voice ->
                voice.locale?.language == "en" &&
                    voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            if (candidates.isEmpty()) return
            val best = candidates.maxWithOrNull(
                compareBy<Voice> { it.quality }
                    .thenBy { if (it.isNetworkConnectionRequired) 0 else 1 }
                    .thenBy { if (it.locale?.country == "US") 1 else 0 },
            ) ?: return
            engine.voice = best
        } catch (e: Exception) {
            Log.w(TAG, "Voice selection failed, using default: ${e.message}")
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "EmulatorSystemTts"
    }
}
