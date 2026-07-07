package com.personaledge.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Fast online STT for x86 emulators. Whisper.cpp takes ~60s per utterance on emulator CPU;
 * Android's SpeechRecognizer returns in 1–3s (requires network + Google speech services).
 */
class EmulatorSpeechStt(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    var onPartial: ((String) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null
    var onRmsDb: ((Float) -> Unit)? = null
    var onMicReady: (() -> Unit)? = null

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
    }

    private val bargeInIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    suspend fun listenForBargeIn(): String = listenInternal(bargeInIntent)

    suspend fun listenOnce(): String = listenInternal(recognizerIntent)

    private suspend fun listenInternal(intent: Intent): String = suspendCancellableCoroutine { cont ->
        runOnMain {
            val sr = ensureRecognizer()
            val listener = object : RecognitionListener {
                private var finished = false

                fun finish(text: String) {
                    if (finished) return
                    finished = true
                    if (cont.isActive) cont.resume(text)
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    finish(text.trim())
                }

                override fun onError(error: Int) {
                    finish("")
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isNotEmpty()) onPartial?.invoke(text)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    onRmsDb?.invoke(rmsdB)
                    val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    onLevel?.invoke(level)
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    onMicReady?.invoke()
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
            sr.setRecognitionListener(listener)
            sr.startListening(intent)
            cont.invokeOnCancellation {
                runOnMain { sr.cancel() }
            }
        }
    }

    fun cancel() {
        runOnMain { recognizer?.cancel() }
    }

    fun destroy() {
        runOnMain {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        return SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer = it }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
