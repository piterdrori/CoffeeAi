package com.personaledge.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File

class SttManager(private val context: Context) {
    private enum class Backend { ANDROID, VOSK }

    private var backend: Backend? = null
    private var voskModel: Model? = null
    private var voskService: SpeechService? = null
    private var androidRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    private val _finalText = MutableStateFlow("")
    val finalText: StateFlow<String> = _finalText

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _backendLabel = MutableStateFlow<String?>(null)
    val backendLabel: StateFlow<String?> = _backendLabel

    fun initModel(onReady: (() -> Unit)? = null) {
        if (_isModelReady.value) {
            onReady?.invoke()
            return
        }
        _isModelLoading.value = true
        _error.value = null

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                androidRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                backend = Backend.ANDROID
                _backendLabel.value = "Android speech (high accuracy)"
                _isModelReady.value = true
                _isModelLoading.value = false
                onReady?.invoke()
                return
            } catch (e: Exception) {
                androidRecognizer = null
            }
        }

        loadVoskModel(onReady)
    }

    private fun loadVoskModel(onReady: (() -> Unit)?) {
        if (voskModel != null) {
            backend = Backend.VOSK
            _backendLabel.value = "Vosk offline"
            _isModelReady.value = true
            _isModelLoading.value = false
            onReady?.invoke()
            return
        }

        val modelDir = File(context.filesDir, "vosk-model")
        if (modelDir.exists() && File(modelDir, "am").exists()) {
            try {
                voskModel = Model(modelDir.absolutePath)
                backend = Backend.VOSK
                _backendLabel.value = "Vosk offline"
                _isModelReady.value = true
                _isModelLoading.value = false
                onReady?.invoke()
            } catch (e: Exception) {
                _isModelLoading.value = false
                _error.value = "Failed to load Vosk model: ${e.message}"
            }
            return
        }

        StorageService.unpack(
            context,
            "model-en-us",
            "model",
            { loaded ->
                voskModel = loaded
                backend = Backend.VOSK
                _backendLabel.value = "Vosk offline"
                _isModelReady.value = true
                _isModelLoading.value = false
                onReady?.invoke()
            },
            { e ->
                _isModelLoading.value = false
                _error.value =
                    "No speech engine available. Run .\\scripts\\setup-vosk-emulator.ps1 — ${e.message}"
            },
        )
    }

    fun startListening() {
        when (backend) {
            Backend.ANDROID -> startAndroidListening()
            Backend.VOSK -> startVoskListening()
            null -> _error.value = "Speech model not loaded yet"
        }
    }

    private fun startAndroidListening() {
        val recognizer = androidRecognizer ?: run {
            _error.value = "Android speech recognizer not ready"
            return
        }
        if (_isListening.value) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                _error.value = null
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _isListening.value = false
            }

            override fun onError(error: Int) {
                _isListening.value = false
                if (error != SpeechRecognizer.ERROR_CLIENT) {
                    _error.value = androidErrorMessage(error)
                }
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    _finalText.value = text
                    _partialText.value = ""
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                _partialText.value = text
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        _finalText.value = ""
        _partialText.value = ""
        _error.value = null
        recognizer.startListening(intent)
    }

    private fun startVoskListening() {
        val m = voskModel ?: run {
            _error.value = "Vosk model not loaded yet"
            return
        }
        if (_isListening.value) return

        val recognizer = Recognizer(m, 16000.0f)
        voskService = SpeechService(recognizer, 16000.0f)
        voskService?.startListening(object : VoskRecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                _partialText.value = extractVoskText(hypothesis)
            }

            override fun onResult(hypothesis: String?) {
                _finalText.value = extractVoskText(hypothesis)
                _partialText.value = ""
            }

            override fun onFinalResult(hypothesis: String?) {
                _finalText.value = extractVoskText(hypothesis)
                _partialText.value = ""
                _isListening.value = false
            }

            override fun onError(exception: Exception?) {
                _error.value = exception?.message ?: "Speech recognition error"
                _isListening.value = false
            }

            override fun onTimeout() {
                _isListening.value = false
            }
        })
        _isListening.value = true
        _error.value = null
        _finalText.value = ""
        _partialText.value = ""
    }

    fun stopListening() {
        when (backend) {
            Backend.ANDROID -> {
                androidRecognizer?.stopListening()
                androidRecognizer?.cancel()
                _isListening.value = false
            }
            Backend.VOSK -> {
                voskService?.stop()
                voskService?.shutdown()
                voskService = null
                _isListening.value = false
            }
            null -> Unit
        }
    }

    fun clearText() {
        _partialText.value = ""
        _finalText.value = ""
    }

    fun capturedText(): String = _finalText.value.ifBlank { _partialText.value }.trim()

    private fun extractVoskText(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        return try {
            val json = org.json.JSONObject(hypothesis)
            json.optString("text", "")
        } catch (_: Exception) {
            hypothesis
        }
    }

    private fun androidErrorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error — enable host mic in emulator settings"
        SpeechRecognizer.ERROR_NETWORK -> "Network error — check emulator internet for speech"
        SpeechRecognizer.ERROR_NO_MATCH -> "Did not catch that — try speaking again"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard — tap orb and speak"
        else -> "Speech error ($code)"
    }

    fun shutdown() {
        stopListening()
        androidRecognizer?.destroy()
        androidRecognizer = null
        voskModel?.close()
        voskModel = null
        backend = null
    }
}
