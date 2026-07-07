package com.personaledge.ai.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperTranscribeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.speech.SpeechRecognizer
import kotlin.math.sqrt

class SttManager(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineMutex = Mutex()
    private val readMutex = Mutex()
    private val transcribeMutex = Mutex()

    private var whisper: WhisperContext? = null
    private var whisperModelPath: String? = null
    private var emulatorStt: EmulatorSpeechStt? = null
    private var audioRecord: AudioRecord? = null
    private var sessionJob: Job? = null
    private var micMonitorJob: Job? = null

    @Volatile
    private var sessionRunning = false

    @Volatile
    private var acceptingUtterance = true

    @Volatile
    private var allowBargeIn = false

    @Volatile
    private var bargeInDuringTts = false

    @Volatile
    private var ttsPlaybackActive = false

    @Volatile
    private var suppressEmptySttErrorOnce = false

    @Volatile
    private var bargeInTriggered = false

    @Volatile
    private var ttsBleedCalibrated = false

    @Volatile
    private var ttsBleedFloor = 0f

    @Volatile
    private var ttsBleedPeak = 0f

    @Volatile
    private var ttsCalibStartedAt = 0L

    @Volatile
    private var previousBargeInRms = 0f

    @Volatile
    private var ttsAdaptiveBaseline = 0f

    @Volatile
    private var bargeInMicEpoch = 0

    private var bargeInRmsThreshold = SherpaVoiceConfig.SPEECH_RMS_THRESHOLD

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    var onPartialText: ((String) -> Unit)? = null
    var onUtteranceComplete: ((String) -> Unit)? = null
    var onBargeIn: (() -> Unit)? = null

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

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing

    private val _transcribeSeconds = MutableStateFlow(0)
    val transcribeSeconds: StateFlow<Int> = _transcribeSeconds

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _backendLabel = MutableStateFlow<String?>(null)
    val backendLabel: StateFlow<String?> = _backendLabel

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    private var srLevelBaselineDb = 0f
    private var srLevelWarmupUntilMs = 0L
    private var srDisplayExcessMs = 0L
    private var monitorBaselineRms = 0f
    private var monitorDisplayExcessMs = 0L

    private val _micConnected = MutableStateFlow(false)
    val micConnected: StateFlow<Boolean> = _micConnected

    private val _micInputSilent = MutableStateFlow(false)
    val micInputSilent: StateFlow<Boolean> = _micInputSilent

    fun startMicMonitor() {
        if (sessionRunning || micMonitorJob?.isActive == true) return
        micMonitorJob = scope.launch {
            try {
                engineMutex.withLock {
                    if (!initMicrophone()) {
                        _micConnected.value = false
                        return@withLock
                    }
                    if (SherpaVoiceConfig.isPhysicalDevice) {
                        _micConnected.value = true
                    }
                }
                monitorMicLevels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Mic monitor failed", e)
            } finally {
                if (!sessionRunning) {
                    releaseMicrophone()
                    _micLevel.value = 0f
                }
            }
        }
    }

    fun stopMicMonitor() {
        micMonitorJob?.cancel()
        micMonitorJob = null
        if (!sessionRunning) {
            releaseMicrophone()
            _micLevel.value = 0f
        }
    }

    private suspend fun stopMicMonitorAndAwait() {
        val job = micMonitorJob
        micMonitorJob = null
        job?.cancelAndJoin()
    }

    private suspend fun readMic(buffer: ShortArray): Int {
        return readMutex.withLock {
            audioRecord?.read(buffer, 0, buffer.size) ?: -1
        }
    }

    private suspend fun monitorMicLevels() {
        val bufferSize = (0.1 * SherpaVoiceConfig.SAMPLE_RATE).toInt()
        val buffer = ShortArray(bufferSize)
        var recentPeak = 0f
        var silentReads = 0
        monitorBaselineRms = 0f
        val warmupEndMs = System.currentTimeMillis() + 1_500L
        withContext(Dispatchers.IO) {
            while (!sessionRunning && isActive) {
                val read = readMic(buffer)
                if (read <= 0) {
                    delay(20)
                    continue
                }
                val chunk = FloatArray(read) { buffer[it] / 32768.0f }
                val rms = computeRms(chunk)
                if (monitorBaselineRms <= 0f) {
                    monitorBaselineRms = rms
                } else if (rms <= monitorBaselineRms * 1.15f) {
                    monitorBaselineRms = monitorBaselineRms * 0.9f + rms * 0.1f
                }
                if (System.currentTimeMillis() < warmupEndMs) {
                    setMicLevelSmooth(0f)
                } else {
                    val excess = (rms - monitorBaselineRms).coerceAtLeast(0f)
                    if (excess >= 0.012f) {
                        monitorDisplayExcessMs += 50L
                    } else {
                        monitorDisplayExcessMs = maxOf(0L, monitorDisplayExcessMs - 50L)
                    }
                    val raw = if (monitorDisplayExcessMs >= 150L) {
                        (excess / 0.025f).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    setMicLevelSmooth(raw)
                }
                if (isAllZero(buffer, read)) {
                    silentReads++
                } else {
                    silentReads = 0
                }
                _micInputSilent.value = silentReads >= 15
                recentPeak = maxOf(recentPeak * 0.95f, rms)
                if (!SherpaVoiceConfig.isPhysicalDevice) {
                    _micConnected.value = recentPeak >= SherpaVoiceConfig.MIN_MIC_SIGNAL
                }
                delay(50)
            }
        }
    }

    private fun isAllZero(buffer: ShortArray, read: Int): Boolean {
        for (i in 0 until read) {
            if (buffer[i] != 0.toShort()) return false
        }
        return true
    }

    private fun publishMicLevel(rms: Float) {
        setMicLevelSmooth((rms / 0.04f).coerceIn(0f, 1f))
    }

    private fun setMicLevelSmooth(raw: Float) {
        val prev = _micLevel.value
        _micLevel.value = (prev * 0.65f + raw.coerceIn(0f, 1f) * 0.35f)
    }

    private fun publishSrMicLevel(rmsDb: Float) {
        if (srLevelBaselineDb == 0f) srLevelBaselineDb = rmsDb
        if (rmsDb < srLevelBaselineDb) {
            srLevelBaselineDb = srLevelBaselineDb * 0.92f + rmsDb * 0.08f
        } else {
            srLevelBaselineDb = srLevelBaselineDb * 0.98f + rmsDb * 0.02f
        }
        if (System.currentTimeMillis() < srLevelWarmupUntilMs) {
            setMicLevelSmooth(0f)
            return
        }
        val excess = (rmsDb - srLevelBaselineDb).coerceAtLeast(0f)
        if (excess >= 1.0f) {
            srDisplayExcessMs += 50L
        } else {
            srDisplayExcessMs = maxOf(0L, srDisplayExcessMs - 50L)
        }
        val raw = if (srDisplayExcessMs >= 150L) {
            (excess / 5f).coerceIn(0f, 1f)
        } else {
            0f
        }
        setMicLevelSmooth(raw)
    }

    private fun resetSrMicBaseline() {
        srLevelBaselineDb = 0f
        srDisplayExcessMs = 0L
        srLevelWarmupUntilMs = System.currentTimeMillis() + 600L
    }

    /**
     * Speaker→mic bleed makes voice barge-in unreliable on a loudspeaker: the phone's mic
     * picks up the AI's own voice and cancels the reply mid-sentence. Only allow listening
     * while the AI speaks when a headset is connected; otherwise the user uses the Stop button.
     */
    fun isVoiceBargeInAvailable(): Boolean = isDuplexInterruptSafe()

    private fun isDuplexInterruptSafe(): Boolean {
        @Suppress("DEPRECATION")
        if (audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn) return true
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

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
                    if (SherpaVoiceConfig.useOnlineStt) {
                        val stt = EmulatorSpeechStt(appContext)
                        if (stt.isAvailable()) {
                            emulatorStt = stt
                            _backendLabel.value = SherpaVoiceConfig.ONLINE_STT_LABEL
                            _isModelReady.value = true
                            Log.i(TAG, "Google Speech STT ready")
                            onReady?.invoke()
                            return@withLock
                        }
                        Log.w(TAG, "Google Speech unavailable — falling back to Whisper")
                    }
                    if (!BundledAssetCopier.hasAssetFile(appContext.assets, WhisperVoiceConfig.MODEL_ASSET)) {
                        _error.value =
                            "Whisper model missing. Rebuild APK with scripts/fetch-whisper-stt.ps1"
                        return@withLock
                    }
                    val modelPath = withContext(Dispatchers.IO) {
                        BundledAssetCopier.ensureAssetFileOnDisk(appContext, WhisperVoiceConfig.MODEL_ASSET)
                    }
                    whisperModelPath = modelPath
                    whisper = WhisperContext.createFromFile(modelPath)
                    _backendLabel.value = WhisperVoiceConfig.STT_LABEL
                    _isModelReady.value = true
                    Log.i(TAG, "Whisper model ready at $modelPath")
                    onReady?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Whisper init failed", e)
                    _error.value = "Failed to load Whisper STT: ${e.message}"
                } finally {
                    _isModelLoading.value = false
                }
            }
        }
    }

    fun startSession() {
        if (SherpaVoiceConfig.useOnlineSttOnEmulator) {
            if (emulatorStt == null || !SpeechRecognizer.isRecognitionAvailable(appContext)) {
                _error.value = "Speech recognition not ready — check internet and Google Play services"
                return
            }
        } else if (whisper == null) {
            _error.value = "Speech model not loaded yet"
            return
        }
        if (sessionRunning) return

        sessionJob = scope.launch {
            try {
                stopMicMonitorAndAwait()
                sessionRunning = true
                acceptingUtterance = true
                _sessionActive.value = true
                _error.value = null
                clearText()
                enterVoiceAudioMode()
                if (SherpaVoiceConfig.useOnlineSttOnEmulator) {
                    _isListening.value = true
                    runEmulatorSpeechSession()
                } else {
                    engineMutex.withLock {
                        if (!initMicrophone()) {
                            _error.value = "Microphone permission required"
                            endSessionInternal()
                            return@withLock
                        }
                        _isListening.value = true
                    }
                    runSession()
                }
            } catch (e: CancellationException) {
                // expected when stopping session
            } catch (e: Exception) {
                Log.e(TAG, "Voice session failed", e)
                _error.value = e.message ?: "Voice session failed"
            } finally {
                endSessionInternal()
            }
        }
    }

    fun stopSession() {
        sessionRunning = false
        _sessionActive.value = false
        allowBargeIn = false
    }

    fun setAcceptingUtterance(accept: Boolean) {
        acceptingUtterance = accept
        if (!accept && !allowBargeIn) {
            if (SherpaVoiceConfig.useOnlineSttOnEmulator) {
                emulatorStt?.cancel()
                _micLevel.value = 0f
            }
        } else if (accept) {
            clearText()
            bargeInTriggered = false
        }
    }

    fun setBargeInEnabled(enabled: Boolean, speaking: Boolean = false) {
        val safe = enabled && isDuplexInterruptSafe()
        allowBargeIn = safe
        bargeInDuringTts = speaking
        bargeInRmsThreshold = if (speaking) {
            SherpaVoiceConfig.BARGE_IN_DURING_TTS_RMS
        } else {
            SherpaVoiceConfig.BARGE_IN_THINKING_RMS
        }
        // #region agent log
        VoiceDebugLog.log(
            hypothesisId = "B",
            location = "SttManager.kt:setBargeInEnabled",
            message = "barge-in gate",
            data = mapOf(
                "requested" to enabled,
                "safe" to safe,
                "speaking" to speaking,
                "duplexSafe" to isDuplexInterruptSafe(),
            ),
        )
        // #endregion
        if (!safe) {
            bargeInTriggered = false
        }
        if (SherpaVoiceConfig.useOnlineSttOnEmulator) {
            if (speaking) {
                emulatorStt?.cancel()
            } else if (!safe) {
                emulatorStt?.cancel()
                releaseMicrophone()
            }
        }
    }

    fun setTtsPlaybackActive(active: Boolean) {
        ttsPlaybackActive = active
        // #region agent log
        VoiceDebugLog.log(
            hypothesisId = "A",
            location = "SttManager.kt:setTtsPlaybackActive",
            message = "tts playback flag",
            data = mapOf(
                "active" to active,
                "allowBargeIn" to allowBargeIn,
                "bargeInDuringTts" to bargeInDuringTts,
                "acceptingUtterance" to acceptingUtterance,
            ),
        )
        // #endregion
        if (active) {
            bargeInTriggered = false
            bargeInDuringTts = true
            bargeInRmsThreshold = SherpaVoiceConfig.BARGE_IN_DURING_TTS_RMS
            resetTtsBleedCalibration()
            bargeInMicEpoch++
            emulatorStt?.cancel()
        } else {
            bargeInTriggered = false
            ttsBleedCalibrated = false
            ttsAdaptiveBaseline = 0f
            previousBargeInRms = 0f
        }
    }

    private fun resetTtsBleedCalibration() {
        ttsBleedCalibrated = false
        ttsBleedFloor = 0f
        ttsBleedPeak = 0f
        ttsAdaptiveBaseline = 0f
        ttsCalibStartedAt = System.currentTimeMillis()
        previousBargeInRms = 0f
    }

    /** Returns true once calibration finished and interrupt detection may run. */
    private fun updateTtsBleedCalibration(rms: Float): Boolean {
        if (!ttsPlaybackActive) {
            ttsBleedCalibrated = false
            return false
        }
        if (ttsBleedCalibrated) return true
        ttsBleedPeak = maxOf(ttsBleedPeak, rms)
        val elapsed = System.currentTimeMillis() - ttsCalibStartedAt
        if (elapsed >= SherpaVoiceConfig.TTS_BLEED_CALIB_MS) {
            ttsBleedFloor = maxOf(
                ttsBleedPeak * SherpaVoiceConfig.TTS_BLEED_FLOOR_MARGIN,
                SherpaVoiceConfig.MIN_MIC_SIGNAL,
            )
            ttsBleedCalibrated = true
            Log.i(TAG, "TTS bleed calibrated: peak=$ttsBleedPeak floor=$ttsBleedFloor")
        }
        return false
    }

    private fun computeTtsBargeThreshold(): Float {
        return maxOf(
            SherpaVoiceConfig.BARGE_IN_USER_MIN_RMS,
            ttsBleedFloor * SherpaVoiceConfig.TTS_BLEED_BARGE_MULTIPLIER,
        )
    }

    private fun updateTtsAdaptiveBaseline(rms: Float) {
        if (ttsAdaptiveBaseline <= 0f) {
            ttsAdaptiveBaseline = rms
            return
        }
        if (rms <= ttsAdaptiveBaseline * 1.12f) {
            ttsAdaptiveBaseline += (rms - ttsAdaptiveBaseline) * SherpaVoiceConfig.TTS_BASELINE_FALL_RATE
        } else {
            ttsAdaptiveBaseline += (rms - ttsAdaptiveBaseline) * SherpaVoiceConfig.TTS_BASELINE_RISE_RATE
        }
    }

    /** True when mic energy looks like near-field user speech, not steady AI speaker bleed. */
    private fun isUserSpeechOverTts(rms: Float, priorRms: Float): Boolean {
        if (!ttsPlaybackActive || !ttsBleedCalibrated) return false
        updateTtsAdaptiveBaseline(rms)
        val excess = rms - ttsAdaptiveBaseline
        if (rms < SherpaVoiceConfig.BARGE_IN_USER_MIN_RMS) return false
        if (excess >= SherpaVoiceConfig.TTS_BARGE_EXCESS_RMS) return true
        val threshold = computeTtsBargeThreshold()
        if (rms < threshold) return false
        val delta = rms - priorRms
        return delta >= SherpaVoiceConfig.BARGE_IN_SPIKE_DELTA ||
            rms >= ttsBleedFloor * SherpaVoiceConfig.TTS_BLEED_SPIKE_MULTIPLIER
    }

    private fun prepareEmulatorRecognizer() {
        releaseMicrophone()
        emulatorStt?.cancel()
    }

    private suspend fun runEmulatorSpeechSession() {
        val stt = emulatorStt ?: return
        stt.onMicReady = { _micConnected.value = true }

        while (sessionRunning) {
            when {
                acceptingUtterance -> listenForUtteranceEmulator(stt)
                allowBargeIn -> {
                    // #region agent log
                    VoiceDebugLog.log(
                        hypothesisId = "D",
                        location = "SttManager.kt:runEmulatorSession",
                        message = "barge-in branch",
                        data = mapOf(
                            "ttsPlaybackActive" to ttsPlaybackActive,
                            "bargeInDuringTts" to bargeInDuringTts,
                        ),
                    )
                    // #endregion
                    if (bargeInDuringTts && !ttsPlaybackActive) {
                        delay(50)
                    } else {
                        monitorBargeInWithMicOnly()
                    }
                }
                else -> {
                    delay(100)
                    _micLevel.value = 0f
                }
            }
        }
    }

    private suspend fun listenForUtteranceEmulator(stt: EmulatorSpeechStt) {
        prepareEmulatorRecognizer()
        delay(200)
        if (!sessionRunning || !acceptingUtterance) return

        stt.onPartial = { text ->
            _partialText.value = text
            onPartialText?.invoke(text)
            _error.value = null
        }
        stt.onLevel = { level ->
            _micConnected.value = level >= SherpaVoiceConfig.MIC_READY_LEVEL
        }
        stt.onRmsDb = { rmsDb ->
            publishSrMicLevel(rmsDb)
            _micConnected.value = rmsDb > -5f
        }
        resetSrMicBaseline()
        _partialText.value = "Listening…"
        onPartialText?.invoke(_partialText.value)
        _error.value = null
        val text = stt.listenOnce()
        if (!sessionRunning) return
        if (!acceptingUtterance) return
        _partialText.value = ""
        if (text.isBlank()) {
            if (suppressEmptySttErrorOnce) {
                suppressEmptySttErrorOnce = false
                return
            }
            _error.value = "Couldn't catch that — speak clearly and pause when done."
            return
        }
        Log.i(TAG, "Emulator STT result: \"$text\"")
        _finalText.value = text
        acceptingUtterance = false
        emulatorStt?.cancel()
        stt.onRmsDb = null
        decayMicLevel()
        onUtteranceComplete?.invoke(text)
        while (sessionRunning && !acceptingUtterance && !allowBargeIn) {
            delay(100)
        }
    }

    /**
     * Emulator barge-in uses mic energy only — never speech recognition during TTS,
     * so the AI's own voice from speakers is not transcribed as user speech.
     */
    private suspend fun monitorBargeInWithMicOnly() {
        monitorBargeInWithMicEnergy()
    }

    private suspend fun monitorBargeInWithMicEnergy() {
        prepareEmulatorRecognizer()
        if (!initMicrophone()) return

        val thinkingThreshold = SherpaVoiceConfig.BARGE_IN_THINKING_RMS
        val holdMs = if (ttsPlaybackActive) {
            SherpaVoiceConfig.BARGE_IN_HOLD_MS
        } else {
            SherpaVoiceConfig.BARGE_IN_THINKING_HOLD_MS
        }

        val bufferSize = (0.1 * SherpaVoiceConfig.SAMPLE_RATE).toInt()
        val buffer = ShortArray(bufferSize)
        var loudMs = 0L
        val chunkMs = 100L
        var priorRms = 0f
        var micEpoch = bargeInMicEpoch
        var noReadMs = 0L

        try {
            withContext(Dispatchers.IO) {
                while (sessionRunning && allowBargeIn) {
                    if (acceptingUtterance) return@withContext
                    if (micEpoch != bargeInMicEpoch) {
                        micEpoch = bargeInMicEpoch
                        priorRms = 0f
                        if (!initMicrophone()) return@withContext
                    }
                    val read = readMic(buffer)
                    if (read <= 0) {
                        noReadMs += 20
                        if (noReadMs >= 200) {
                            initMicrophone()
                            noReadMs = 0
                        }
                        delay(20)
                        continue
                    }
                    noReadMs = 0
                    val chunk = FloatArray(read) { buffer[it] / 32768.0f }
                    val rms = computeRms(chunk)
                    val displayThreshold = if (ttsPlaybackActive && ttsBleedCalibrated) {
                        computeTtsBargeThreshold()
                    } else {
                        thinkingThreshold
                    }
                    val level = (rms / displayThreshold).coerceIn(0f, 1f)
                    setMicLevelSmooth(level * 0.5f)
                    _micConnected.value = true

                    val userOverTts = if (ttsPlaybackActive) {
                        updateTtsBleedCalibration(rms)
                        val over = isUserSpeechOverTts(rms, priorRms)
                        // #region agent log
                        if (over) {
                            VoiceDebugLog.log(
                                hypothesisId = "C",
                                location = "SttManager.kt:ttsMicSample",
                                message = "TTS user speech candidate",
                                data = mapOf(
                                    "rms" to rms,
                                    "baseline" to ttsAdaptiveBaseline,
                                    "excess" to (rms - ttsAdaptiveBaseline),
                                ),
                            )
                        }
                        // #endregion
                        over
                    } else if (bargeInDuringTts) {
                        false
                    } else {
                        rms >= thinkingThreshold
                    }
                    priorRms = rms
                    previousBargeInRms = rms

                    if (userOverTts) {
                        loudMs += chunkMs
                        if (loudMs >= holdMs && !bargeInTriggered) {
                            Log.i(
                                TAG,
                                "Barge-in: user speech detected (rms=$rms floor=$ttsBleedFloor tts=$ttsPlaybackActive)",
                            )
                            bargeInTriggered = true
                            suppressEmptySttErrorOnce = true
                            onBargeIn?.invoke()
                            return@withContext
                        }
                    } else {
                        loudMs = 0L
                    }
                    delay(50)
                }
            }
        } finally {
            releaseMicrophone()
            bargeInTriggered = false
            decayMicLevel()
        }
    }

    private fun decayMicLevel() {
        _micLevel.value = _micLevel.value * 0.5f
    }

    private suspend fun runSession() {
        while (sessionRunning) {
            val utterance = collectUtterance()
            if (!sessionRunning) break
            val text = utterance.trim()
            if (text.isNotBlank()) {
                _finalText.value = text
                _partialText.value = ""
                onUtteranceComplete?.invoke(text)
            } else {
                _partialText.value = ""
                _error.value = "Couldn't understand that — speak clearly and pause when done."
            }
        }
    }

    private suspend fun collectUtterance(): String {
        val samples = ArrayList<Float>(SherpaVoiceConfig.SAMPLE_RATE * 30)
        var hasSpeech = false
        var lastSpeechAtMs = System.currentTimeMillis()
        var speechStartedAtMs = 0L

        val bufferSize = (0.1 * SherpaVoiceConfig.SAMPLE_RATE).toInt()
        val buffer = ShortArray(bufferSize)
        val noiseFloor = measureNoiseFloor(buffer)
        var baseline = noiseFloor.coerceAtLeast(SherpaVoiceConfig.SPEECH_RMS_THRESHOLD * 0.25f)
        Log.i(
            TAG,
            "Noise floor=$noiseFloor baseline=$baseline " +
                "speechOn=${baseline * SherpaVoiceConfig.SPEECH_BASELINE_MULTIPLIER} " +
                "speechOff=${baseline * SherpaVoiceConfig.SILENCE_BASELINE_MULTIPLIER}",
        )

        val utteranceStartedAtMs = System.currentTimeMillis()
        var sessionPeak = noiseFloor
        var noSignalWarned = false
        var bargeInLoudMs = 0L

        return withContext(Dispatchers.IO) {
            while (sessionRunning) {
                val read = readMic(buffer)
                if (read <= 0) {
                    delay(10)
                    continue
                }

                val chunk = FloatArray(read) { buffer[it] / 32768.0f }
                val rms = computeRms(chunk)
                publishMicLevel(rms)
                sessionPeak = maxOf(sessionPeak, rms)
                _micConnected.value = rms >= SherpaVoiceConfig.MIN_MIC_SIGNAL ||
                    _micLevel.value >= SherpaVoiceConfig.MIC_READY_LEVEL
                val now = System.currentTimeMillis()

                if (!hasSpeech || rms < baseline * SherpaVoiceConfig.SPEECH_BASELINE_MULTIPLIER) {
                    baseline = baseline * 0.985f + rms * 0.015f
                }
                val speechOnThreshold = maxOf(
                    SherpaVoiceConfig.SPEECH_RMS_THRESHOLD,
                    baseline * SherpaVoiceConfig.SPEECH_BASELINE_MULTIPLIER,
                )
                val speechOffThreshold = maxOf(
                    SherpaVoiceConfig.SPEECH_RMS_THRESHOLD * 0.5f,
                    baseline * SherpaVoiceConfig.SILENCE_BASELINE_MULTIPLIER,
                )

                if (!hasSpeech &&
                    !noSignalWarned &&
                    now - utteranceStartedAtMs >= 3_000L &&
                    sessionPeak < SherpaVoiceConfig.MIN_MIC_SIGNAL
                ) {
                    noSignalWarned = true
                    _error.value =
                        "No microphone signal detected. Restart emulator with -allow-host-audio, then enable ⋯ → Microphone → Host audio input."
                }

                if (allowBargeIn) {
                    if (ttsPlaybackActive) {
                        updateTtsBleedCalibration(rms)
                        if (isUserSpeechOverTts(rms, previousBargeInRms)) {
                            bargeInLoudMs += 100L
                            if (bargeInLoudMs >= SherpaVoiceConfig.BARGE_IN_HOLD_MS && !bargeInTriggered) {
                                Log.i(TAG, "Barge-in: user speech over TTS (rms=$rms floor=$ttsBleedFloor)")
                                bargeInTriggered = true
                                onBargeIn?.invoke()
                                return@withContext ""
                            }
                        } else {
                            bargeInLoudMs = 0L
                        }
                        previousBargeInRms = rms
                    } else if (bargeInDuringTts) {
                        if (rms >= bargeInRmsThreshold) {
                            bargeInLoudMs += 100L
                            if (bargeInLoudMs >= SherpaVoiceConfig.BARGE_IN_HOLD_MS && !bargeInTriggered) {
                                Log.i(TAG, "Barge-in: user speech over TTS (rms=$rms)")
                                bargeInTriggered = true
                                onBargeIn?.invoke()
                                return@withContext ""
                            }
                        } else {
                            bargeInLoudMs = 0L
                        }
                    } else if (rms >= bargeInRmsThreshold && !bargeInTriggered) {
                        bargeInTriggered = true
                        onBargeIn?.invoke()
                        return@withContext ""
                    } else if (rms < bargeInRmsThreshold * 0.5f) {
                        bargeInTriggered = false
                    }
                }

                if (!acceptingUtterance) {
                    continue
                }

                if (!hasSpeech && rms >= speechOnThreshold) {
                    hasSpeech = true
                    speechStartedAtMs = now
                    samples.clear()
                    lastSpeechAtMs = now
                } else if (hasSpeech && rms >= speechOnThreshold) {
                    lastSpeechAtMs = now
                }

                if (hasSpeech) {
                    for (sample in chunk) {
                        samples.add(sample)
                    }
                    _partialText.value = "Listening…"
                    onPartialText?.invoke(_partialText.value)

                    val silentFor = now - lastSpeechAtMs
                    val spokeFor = now - speechStartedAtMs
                    val isSilent = rms < speechOffThreshold
                    val hitMaxLength = samples.size >= SherpaVoiceConfig.MAX_UTTERANCE_SAMPLES ||
                        spokeFor >= SherpaVoiceConfig.MAX_UTTERANCE_MS
                    if (hitMaxLength && spokeFor >= SherpaVoiceConfig.MIN_UTTERANCE_MS) {
                        return@withContext transcribeSamples(trimUtteranceSamples(samples))
                    }
                    if (isSilent &&
                        silentFor >= SherpaVoiceConfig.ENDPOINT_SILENCE_MS &&
                        spokeFor >= SherpaVoiceConfig.MIN_UTTERANCE_MS &&
                        samples.size >= SherpaVoiceConfig.MIN_UTTERANCE_SAMPLES
                    ) {
                        return@withContext transcribeSamples(trimUtteranceSamples(samples))
                    }
                }
            }
            ""
        }
    }

    private suspend fun measureNoiseFloor(buffer: ShortArray): Float {
        var peak = 0f
        var sum = 0.0
        var count = 0
        val endAt = System.currentTimeMillis() + 800
        while (System.currentTimeMillis() < endAt && sessionRunning) {
            val read = readMic(buffer)
            if (read <= 0) {
                delay(10)
                continue
            }
            val chunk = FloatArray(read) { buffer[it] / 32768.0f }
            val rms = computeRms(chunk)
            peak = maxOf(peak, rms)
            sum += rms
            count++
        }
        val average = if (count == 0) SherpaVoiceConfig.SPEECH_RMS_THRESHOLD else (sum / count).toFloat()
        return maxOf(average, peak * 0.35f)
    }

    private fun trimUtteranceSamples(samples: ArrayList<Float>): FloatArray {
        val max = SherpaVoiceConfig.MAX_UTTERANCE_SAMPLES
        if (samples.size <= max) return samples.toFloatArray()
        Log.w(TAG, "Trimming utterance from ${samples.size} to $max samples")
        return samples.takeLast(max).toFloatArray()
    }

    private suspend fun transcribeSamples(samples: FloatArray): String {
        val engine = whisper ?: return ""
        if (samples.isEmpty()) return ""
        val durationSec = samples.size.toFloat() / SherpaVoiceConfig.SAMPLE_RATE
        Log.i(TAG, "Transcribing ${"%.1f".format(durationSec)}s of audio (${samples.size} samples)")

        _isTranscribing.value = true
        _partialText.value = "Transcribing…"
        onPartialText?.invoke(_partialText.value)
        val tickJob = scope.launch {
            var seconds = 0
            while (true) {
                delay(1_000)
                seconds++
                _transcribeSeconds.value = seconds
            }
        }

        return try {
            transcribeMutex.withLock {
                val result = withContext(Dispatchers.IO) {
                    engine.transcribeWithTimeout(samples, SherpaVoiceConfig.TRANSCRIBE_TIMEOUT_MS)
                }
                when (result) {
                    is WhisperTranscribeResult.Success -> {
                        val text = result.text
                            .removePrefix("[BLANK_AUDIO]")
                            .trim()
                        Log.i(TAG, "Whisper result: \"$text\" (${samples.size} samples)")
                        text
                    }
                    WhisperTranscribeResult.TimedOut -> {
                        _error.value = "Transcription timed out — speak a shorter phrase and try again."
                        scope.launch { reloadWhisperAfterTimeout() }
                        ""
                    }
                    is WhisperTranscribeResult.Error -> {
                        _error.value = "Transcription failed: ${result.message}"
                        scope.launch { reloadWhisperAfterTimeout() }
                        ""
                    }
                }
            }
        } finally {
            tickJob.cancel()
            _transcribeSeconds.value = 0
            _isTranscribing.value = false
            _partialText.value = ""
        }
    }

    private suspend fun reloadWhisperAfterTimeout() {
        val path = whisperModelPath ?: return
        engineMutex.withLock {
            val current = whisper ?: return@withLock
            Log.w(TAG, "Reloading Whisper context after timeout/error")
            if (!current.reload()) {
                whisper = WhisperContext.createFromFile(path)
            }
        }
    }

    private fun endSessionInternal() {
        _isListening.value = false
        _isTranscribing.value = false
        emulatorStt?.cancel()
        releaseMicrophone()
        exitVoiceAudioMode()
        sessionRunning = false
        acceptingUtterance = false
        bargeInDuringTts = false
        ttsPlaybackActive = false
        suppressEmptySttErrorOnce = false
        ttsBleedCalibrated = false
        ttsBleedFloor = 0f
        ttsBleedPeak = 0f
        previousBargeInRms = 0f
        _sessionActive.value = false
        allowBargeIn = false
        if (_micLevel.value == 0f && !SherpaVoiceConfig.useOnlineSttOnEmulator) {
            _micConnected.value = false
        }
    }

    fun clearText() {
        _partialText.value = ""
        _finalText.value = ""
        _error.value = null
    }

    private fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            sum += s * s
        }
        return sqrt(sum / samples.size).toFloat()
    }

    private fun releaseMicrophone() {
        releaseAudioEffects()
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
    }

    private fun releaseAudioEffects() {
        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        noiseSuppressor = null
    }

    private fun enableAudioEffects(sessionId: Int) {
        releaseAudioEffects()
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
        }
    }

    private fun enterVoiceAudioMode() {
        if (!SherpaVoiceConfig.useOnlineSttOnEmulator) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    private fun exitVoiceAudioMode() {
        if (!SherpaVoiceConfig.useOnlineSttOnEmulator) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun initMicrophone(): Boolean {
        if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            return true
        }
        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord?.startRecording()
            return audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }
        releaseMicrophone()

        val minBytes = AudioRecord.getMinBufferSize(
            SherpaVoiceConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes == AudioRecord.ERROR || minBytes == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }

        audioRecord = buildAudioRecord(pickAudioSource(), minBytes)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return tryFallbackMicrophone(minBytes)
        }
        audioRecord?.startRecording()
        audioRecord?.audioSessionId?.let { enableAudioEffects(it) }
        return audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    private fun buildAudioRecord(source: Int, minBytes: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SherpaVoiceConfig.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBytes * 4)
            .build()
    }

    private fun pickAudioSource(): Int {
        if (SherpaVoiceConfig.useOnlineSttOnEmulator) {
            return MediaRecorder.AudioSource.MIC
        }
        return MediaRecorder.AudioSource.VOICE_COMMUNICATION
    }

    private fun tryFallbackMicrophone(minBytes: Int): Boolean {
        for (source in FALLBACK_AUDIO_SOURCES) {
            audioRecord = buildAudioRecord(source, minBytes)
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                audioRecord?.audioSessionId?.let { enableAudioEffects(it) }
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    Log.i(TAG, "Microphone opened with audio source $source")
                    return true
                }
            }
            audioRecord?.release()
            audioRecord = null
        }
        return false
    }

    suspend fun shutdownAndAwait() {
        stopSession()
        sessionJob?.join()
        engineMutex.withLock {
            whisper?.release()
            whisper = null
            whisperModelPath = null
            emulatorStt?.destroy()
            emulatorStt = null
            _isModelReady.value = false
        }
    }

    fun shutdown() {
        scope.launch { shutdownAndAwait() }
    }

    companion object {
        private const val TAG = "SttManager"
        private val FALLBACK_AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER,
        )
    }
}
