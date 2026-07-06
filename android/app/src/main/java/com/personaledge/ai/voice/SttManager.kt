package com.personaledge.ai.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SttManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineMutex = Mutex()

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var stream: OnlineStream? = null
    private var listenJob: Job? = null

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
        scope.launch {
            engineMutex.withLock {
                if (_isModelReady.value) {
                    onReady?.invoke()
                    return@withLock
                }
                _isModelLoading.value = true
                _error.value = null
                try {
                    if (!BundledAssetCopier.hasAssetDir(appContext.assets, SherpaVoiceConfig.STT_ASSET_DIR)) {
                        _error.value =
                            "Built-in STT model missing. Rebuild APK with scripts/fetch-bundled-voice-models.ps1"
                        return@withLock
                    }
                    val modelRoot = withContext(Dispatchers.IO) {
                        BundledAssetCopier.ensureDirOnDisk(appContext, SherpaVoiceConfig.STT_ASSET_DIR)
                    }
                    recognizer = OnlineRecognizer(
                        assetManager = null,
                        config = SherpaVoiceConfig.sttRecognizerConfig(modelRoot),
                    )
                    _backendLabel.value = SherpaVoiceConfig.STT_LABEL
                    _isModelReady.value = true
                    onReady?.invoke()
                } catch (e: Exception) {
                    _error.value = "Failed to load built-in STT: ${e.message}"
                } finally {
                    _isModelLoading.value = false
                }
            }
        }
    }

    fun startListening() {
        val rec = recognizer ?: run {
            _error.value = "Speech model not loaded yet"
            return
        }
        if (_isListening.value) return

        listenJob?.cancel()
        listenJob = scope.launch {
            try {
                engineMutex.withLock {
                    if (!initMicrophone()) {
                        _error.value = "Microphone permission required"
                        return@withLock
                    }
                    stream?.release()
                    stream = rec.createStream()
                    _finalText.value = ""
                    _partialText.value = ""
                    _error.value = null
                    _isListening.value = true
                }
                processSamples(rec)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to start listening"
                stopListening()
            }
        }
    }

    private suspend fun processSamples(rec: OnlineRecognizer) {
        val localStream = stream ?: return
        val bufferSize = (0.1 * SherpaVoiceConfig.SAMPLE_RATE).toInt()
        val buffer = ShortArray(bufferSize)

        withContext(Dispatchers.IO) {
            while (isActive && _isListening.value) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                localStream.acceptWaveform(samples, sampleRate = SherpaVoiceConfig.SAMPLE_RATE)
                decodeLatest(rec, localStream, finalize = false)
            }
        }

        engineMutex.withLock {
            finalizeDecode(rec, localStream)
            releaseMicrophone()
            stream?.release()
            stream = null
            _isListening.value = false
        }
    }

    private fun decodeLatest(rec: OnlineRecognizer, localStream: OnlineStream, finalize: Boolean) {
        if (finalize) {
            localStream.inputFinished()
        }
        while (rec.isReady(localStream)) {
            rec.decode(localStream)
        }
        val text = rec.getResult(localStream).text.trim()
        if (text.isNotBlank()) {
            if (finalize || rec.isEndpoint(localStream)) {
                _finalText.value = text
                _partialText.value = ""
                if (!finalize) {
                    rec.reset(localStream)
                }
            } else {
                _partialText.value = text
            }
        }
    }

    private fun finalizeDecode(rec: OnlineRecognizer, localStream: OnlineStream) {
        decodeLatest(rec, localStream, finalize = true)
    }

    fun stopListening() {
        _isListening.value = false
        listenJob?.cancel()
        listenJob = null
    }

    fun clearText() {
        _partialText.value = ""
        _finalText.value = ""
    }

    fun capturedText(): String = _finalText.value.ifBlank { _partialText.value }.trim()

    private fun releaseMicrophone() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun initMicrophone(): Boolean {
        val minBytes = AudioRecord.getMinBufferSize(
            SherpaVoiceConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes == AudioRecord.ERROR || minBytes == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SherpaVoiceConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes * 4,
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return false
        }
        audioRecord?.startRecording()
        return true
    }

    suspend fun shutdownAndAwait() {
        stopListening()
        engineMutex.withLock {
            releaseMicrophone()
            stream?.release()
            stream = null
            recognizer?.release()
            recognizer = null
            _isModelReady.value = false
        }
    }

    fun shutdown() {
        scope.launch { shutdownAndAwait() }
    }
}
