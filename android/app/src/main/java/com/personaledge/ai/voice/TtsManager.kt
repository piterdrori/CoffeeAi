package com.personaledge.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.abs
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
import kotlin.coroutines.coroutineContext
import java.io.File

class TtsManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val engineMutex = Mutex()

    private var tts: OfflineTts? = null
    private var emulatorTts: EmulatorSystemTts? = null
    private var audioTrack: AudioTrack? = null
    private var speakJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val speakQueue = ArrayDeque<String>()

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
        scope.launch {
            if (SherpaVoiceConfig.useSystemTts) {
                initSystemTts()
            } else {
                initEngine()
            }
        }
    }

    private suspend fun initSystemTts() {
        try {
            val engine = EmulatorSystemTts(appContext)
            if (!engine.init()) {
                _error.value = "Android text-to-speech is unavailable on this device"
                return
            }
            emulatorTts = engine
            _voiceLabel.value = if (SherpaVoiceConfig.useSystemTts) {
                SherpaVoiceConfig.SYSTEM_TTS_LABEL
            } else {
                "Android TTS (device)"
            }
            _isReady.value = true
            Log.i(TAG, "Emulator system TTS ready")
        } catch (e: Exception) {
            _error.value = "Failed to init system TTS: ${e.message}"
        }
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
            Log.e(TAG, "Piper TTS init failed", e)
            if (SherpaVoiceConfig.isPhysicalDevice) {
                Log.w(TAG, "Falling back to Android system TTS on device")
                initSystemTts()
            } else {
                _error.value = "Failed to load built-in TTS: ${e.message}"
            }
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val useMediaStream = SherpaVoiceConfig.isPhysicalDevice || SherpaVoiceConfig.isX86Device
        val attr = AudioAttributes.Builder()
            .setUsage(
                if (useMediaStream) {
                    AudioAttributes.USAGE_MEDIA
                } else {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setLegacyStreamType(
                if (useMediaStream) {
                    AudioManager.STREAM_MUSIC
                } else {
                    AudioManager.STREAM_VOICE_CALL
                },
            )
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        audioTrack = AudioTrack(
            attr,
            format,
            bufferSize.coerceAtLeast(sampleRate),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrack?.setVolume(1f)
    }

    private fun onAudioChunk(samples: FloatArray): Int {
        if (stopped || released) {
            audioTrack?.stop()
            return 0
        }
        amplifySamples(samples, outputGain)
        audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        return 1
    }

    /** Extra multiplier after peak-normalization (emulator host audio is often very quiet). */
    private val outputGain: Float
        get() = if (SherpaVoiceConfig.isX86Device) 2.5f else 1.5f

    /** Scale each chunk to ~full scale; Piper output is often far below 1.0. */
    private fun amplifySamples(samples: FloatArray, gain: Float) {
        var peak = 0f
        for (s in samples) {
            val level = abs(s)
            if (level > peak) peak = level
        }
        if (peak < 1e-6f) return
        val normalize = (0.98f / peak).coerceAtMost(24f)
        val totalGain = normalize * gain
        for (i in samples.indices) {
            samples[i] = (samples[i] * totalGain).coerceIn(-1f, 1f)
        }
    }

    fun speak(text: String) {
        if (released || !_isReady.value || text.isBlank()) return
        val cleaned = text
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            .replace(Regex("\\*\\*|__"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return

        if (_isSpeaking.value) {
            speakQueue.addLast(cleaned)
            return
        }

        speakJob?.cancel()
        speakJob = scope.launch {
            // #region agent log
            VoiceDebugLog.log(
                hypothesisId = "B",
                location = "TtsManager.kt:speak",
                message = "TTS speak start",
                data = mapOf("chars" to cleaned.length, "emulator" to SherpaVoiceConfig.useSystemTtsOnEmulator),
            )
            // #endregion
            if (SherpaVoiceConfig.useSystemTts || emulatorTts != null) {
                speakWithSystemTts(cleaned)
                return@launch
            }
            speakWithPiper(cleaned)
        }
    }

    private suspend fun speakWithSystemTts(cleaned: String) {
        val engine = emulatorTts ?: return
        ensureAudibleVolume()
        requestPlaybackFocus()
        stopped = false
        _isSpeaking.value = true
        try {
            Log.i(TAG, "Speaking via system TTS (${cleaned.length} chars)")
            val ok = engine.speak(cleaned)
            if (!ok) {
                _error.value = "System TTS playback failed"
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "TTS failed"
        } finally {
            _isSpeaking.value = false
            abandonPlaybackFocus()
            if (!stopped) {
                drainSpeakQueue()
            }
        }
    }

    private fun drainSpeakQueue() {
        if (stopped) return
        val next = speakQueue.removeFirstOrNull() ?: run {
            onSpeakComplete?.invoke()
            return
        }
        speak(next)
    }

    private suspend fun speakWithPiper(cleaned: String) {
        engineMutex.withLock {
            if (released || !coroutineContext.isActive) return@withLock
            val engine = tts ?: return@withLock
            ensureAudibleVolume()
            requestPlaybackFocus()
            stopped = false
            _isSpeaking.value = true
            try {
                withContext(Dispatchers.IO) {
                    if (!coroutineContext.isActive || released) return@withContext
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.setVolume(1f)
                    audioTrack?.play()
                    engine.generateWithConfigAndCallback(
                        text = cleaned,
                        config = GenerationConfig(sid = 0, speed = 0.95f),
                        callback = ::onAudioChunk,
                    )
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    _error.value = e.message ?: "TTS failed"
                }
                } finally {
                    _isSpeaking.value = false
                    audioTrack?.pause()
                    audioTrack?.flush()
                    abandonPlaybackFocus()
                    if (!stopped) {
                        drainSpeakQueue()
                    }
                }
        }
    }

    private fun ensureAudibleVolume() {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        audioManager.setStreamVolume(stream, max, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        }
        // Emulator cold boots often leave notification/ring streams muted; unmute all playback streams.
        if (SherpaVoiceConfig.isX86Device) {
            listOf(
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_ALARM,
            ).forEach { s ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                }
            }
        }
    }

    private fun requestPlaybackFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonPlaybackFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun stop() {
        // #region agent log
        VoiceDebugLog.log(
            hypothesisId = "E",
            location = "TtsManager.kt:stop",
            message = "TTS stop called",
            data = mapOf("wasSpeaking" to _isSpeaking.value),
        )
        // #endregion
        stopped = true
        speakQueue.clear()
        speakJob?.cancel()
        speakJob = null
        _isSpeaking.value = false
        emulatorTts?.stop()
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
            emulatorTts?.shutdown()
            emulatorTts = null
            audioTrack?.release()
            audioTrack = null
        }
        scope.cancel()
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
