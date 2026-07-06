package com.personaledge.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class TtsManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val engineMutex = Mutex()

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var speakJob: Job? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var released = false

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _voiceLabel = MutableStateFlow<String?>(null)
    val voiceLabel: StateFlow<String?> = _voiceLabel

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var autoReadReplies: Boolean = true
    var onSpeakComplete: (() -> Unit)? = null

    init {
        scope.launch { initEngine() }
    }

    private suspend fun initEngine() {
        try {
            if (!BundledAssetCopier.hasAssetDir(appContext.assets, SherpaVoiceConfig.TTS_ASSET_DIR)) {
                _error.value =
                    "Built-in TTS model missing. Rebuild APK with scripts/fetch-bundled-voice-models.ps1"
                return
            }
            val modelRoot = withContext(Dispatchers.IO) {
                BundledAssetCopier.ensureDirOnDisk(appContext, SherpaVoiceConfig.TTS_ASSET_DIR)
            }
            val dataDir = File(modelRoot, "espeak-ng-data").absolutePath
            engineMutex.withLock {
                if (released) return
                val engine = OfflineTts(
                    assetManager = null,
                    config = SherpaVoiceConfig.ttsConfig(modelRoot, dataDir),
                )
                initAudioTrack(engine.sampleRate())
                tts = engine
            }
            _voiceLabel.value = SherpaVoiceConfig.TTS_LABEL
            _isReady.value = true
        } catch (e: Exception) {
            _error.value = "Failed to load built-in TTS: ${e.message}"
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        audioTrack = AudioTrack(
            attr,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    private fun onAudioChunk(samples: FloatArray): Int {
        if (stopped || released) {
            audioTrack?.stop()
            return 0
        }
        audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        return 1
    }

    fun speak(text: String) {
        if (released || !_isReady.value || text.isBlank()) return
        val cleaned = text
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            .replace(Regex("\\*\\*|__"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return

        speakJob?.cancel()
        speakJob = scope.launch {
            engineMutex.withLock {
                if (released || !isActive) return@withLock
                val engine = tts ?: return@withLock
                ensureAudibleVolume()
                stopped = false
                _isSpeaking.value = true
                try {
                    withContext(Dispatchers.IO) {
                        if (!isActive || released) return@withContext
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                        engine.generateWithConfigAndCallback(
                            text = cleaned,
                            config = GenerationConfig(sid = 0, speed = 0.95f),
                            callback = ::onAudioChunk,
                        )
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        _error.value = e.message ?: "TTS failed"
                    }
                } finally {
                    if (isActive) {
                        stopped = true
                        _isSpeaking.value = false
                        audioTrack?.pause()
                        audioTrack?.flush()
                        onSpeakComplete?.invoke()
                    }
                }
            }
        }
    }

    private fun ensureAudibleVolume() {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        val target = (max * 0.92f).toInt().coerceAtLeast(1)
        val current = audioManager.getStreamVolume(stream)
        if (current < target) {
            audioManager.setStreamVolume(stream, target, 0)
        }
    }

    fun stop() {
        stopped = true
        speakJob?.cancel()
        speakJob = null
        _isSpeaking.value = false
        audioTrack?.pause()
        audioTrack?.flush()
    }

    /** Only for process teardown; screens should call [stop] when leaving. */
    suspend fun shutdownAndAwait() {
        engineMutex.withLock {
            if (released) return
            released = true
            stopped = true
            speakJob?.cancel()
            speakJob?.join()
            speakJob = null
            try {
                tts?.release()
            } catch (_: Exception) {
            }
            tts = null
            audioTrack?.release()
            audioTrack = null
        }
        scope.cancel()
    }
}
