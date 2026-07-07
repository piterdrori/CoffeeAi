package com.personaledge.ai.voice

import android.os.Build
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig

object SherpaVoiceConfig {
    const val TTS_ASSET_DIR = "voice/tts/vits-piper-en_US-lessac-medium"
    const val TTS_LABEL = "Piper Lessac (built-in)"

    const val SAMPLE_RATE = 16_000
    const val ENDPOINT_SILENCE_MS = 2_500L
    const val SPEECH_RMS_THRESHOLD = 0.005f
    /** Speech must exceed the adaptive baseline by this factor. */
    const val SPEECH_BASELINE_MULTIPLIER = 3.5f
    /** End turn when RMS drops below baseline × this factor. */
    const val SILENCE_BASELINE_MULTIPLIER = 1.8f
    const val SILENCE_RMS_MULTIPLIER = 2.5f
    /** Minimum RMS to treat host mic as routed (above emulator silence ~1e-5). */
    const val MIN_MIC_SIGNAL = 0.003f
    /** Bar level (~8%) user should reach when speaking before Start is enabled. */
    const val MIC_READY_LEVEL = 0.08f
    const val MIN_UTTERANCE_MS = 400L
    const val MIN_UTTERANCE_SAMPLES = SAMPLE_RATE / 2

    /** x86 emulators run Whisper 10–20× slower than phones. */
    val isX86Device: Boolean = Build.SUPPORTED_ABIS.any { it.startsWith("x86") }

    /** True on AVD / SDK images — not on physical handsets. */
    val isEmulator: Boolean = run {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()
        fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            fingerprint.contains("ranchu") ||
            model.contains("google_sdk") ||
            model.contains("emulator") ||
            model.contains("android sdk built for x86") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk_gphone") ||
            product == "google_sdk" ||
            product.contains("emulator")
    }

    val isPhysicalDevice: Boolean = !isEmulator

    /** Legacy fixed floor — real detection uses adaptive [ttsBleedFloor] while TTS plays. */
    val BARGE_IN_DURING_TTS_RMS: Float get() = if (isX86Device) 0.07f else 0.042f
    const val BARGE_IN_HOLD_MS = 450L
    const val BARGE_IN_THINKING_HOLD_MS = 300L
    /** Barge-in while thinking (no TTS) — normal sensitivity. */
    const val BARGE_IN_THINKING_RMS = 0.012f
    /** Minimum RMS that can count as user speech over TTS (near-field voice). */
    const val BARGE_IN_USER_MIN_RMS = 0.018f
    /** Measure speaker bleed at TTS start before enabling interrupt detection. */
    const val TTS_BLEED_CALIB_MS = 450L
    /** Margin applied to measured bleed peak when building the adaptive floor. */
    const val TTS_BLEED_FLOOR_MARGIN = 1.35f
    /** User RMS must exceed bleed floor × this to count as interrupt. */
    const val TTS_BLEED_BARGE_MULTIPLIER = 2.8f
    /** Instant interrupt when RMS jumps this far above the calibrated bleed floor. */
    const val TTS_BLEED_SPIKE_MULTIPLIER = 2.2f
    /** Frame-to-frame RMS rise that indicates near-field user speech, not steady bleed. */
    const val BARGE_IN_SPIKE_DELTA = 0.018f
    /** dB rise above calibrated bleed that counts as user speech (SpeechRecognizer path). */
    const val TTS_SR_ENERGY_DB_DELTA = 2.5f
    /** Min ms of elevated mic energy before SR partial can trigger barge-in. */
    const val TTS_SR_ENERGY_HOLD_MS = 120L
    /** Excess RMS above adaptive bleed baseline for mic-only TTS barge-in. */
    val TTS_BARGE_EXCESS_RMS: Float get() = if (isX86Device) 0.006f else 0.012f
    const val TTS_BASELINE_FALL_RATE = 0.14f
    const val TTS_BASELINE_RISE_RATE = 0.02f

    /** Whisper on emulator is too slow; use online Google STT for dev instead. */
    val useOnlineSttOnEmulator: Boolean = isEmulator
    /** Piper TTS often silent on emulator; use Android system TTS for audible PC output. */
    val useSystemTtsOnEmulator: Boolean = isEmulator
    const val EMULATOR_STT_LABEL = "Google Speech (emulator — online)"
    const val EMULATOR_TTS_LABEL = "Android TTS (emulator)"

    val MAX_UTTERANCE_MS: Long = if (isX86Device) 2_000L else 4_000L
    val MAX_UTTERANCE_SAMPLES: Int = if (isX86Device) SAMPLE_RATE * 2 else SAMPLE_RATE * 4
    val TRANSCRIBE_TIMEOUT_MS: Long = if (isX86Device) 60_000L else 25_000L

    fun ttsConfig(modelRoot: String, dataDir: String): OfflineTtsConfig {
        return getOfflineTtsConfig(
            modelDir = modelRoot,
            modelName = "en_US-lessac-medium.onnx",
            acousticModelName = "",
            vocoder = "",
            voices = "",
            lexicon = "",
            dataDir = dataDir,
            dictDir = "",
            ruleFsts = "",
            ruleFars = "",
            numThreads = 2,
        )
    }
}

object WhisperVoiceConfig {
    const val MODEL_ASSET = "voice/stt/ggml-tiny.en.bin"
    const val STT_LABEL = "Whisper.cpp tiny.en (built-in)"
}
