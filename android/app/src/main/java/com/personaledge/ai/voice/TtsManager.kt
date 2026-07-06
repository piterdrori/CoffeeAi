package com.personaledge.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _voiceLabel = MutableStateFlow<String?>(null)
    val voiceLabel: StateFlow<String?> = _voiceLabel

    var autoReadReplies: Boolean = true
    var onSpeakComplete: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            configureOfflineVoice()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    onSpeakComplete?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    onSpeakComplete?.invoke()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    onSpeakComplete?.invoke()
                }
            })
            _isReady.value = true
        }
    }

    private fun configureOfflineVoice() {
        val engine = tts ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build(),
            )
        }

        val voices = engine.voices ?: emptySet()
        val offlineEnglish = voices.filter { voice ->
            voice.locale.language.equals("en", ignoreCase = true) &&
                !voice.name.contains("fallback", true) &&
                !voice.isNetworkConnectionRequired
        }
        val preferred = offlineEnglish
            .sortedWith(
                compareByDescending<Voice> { voice ->
                    when {
                        voice.quality >= Voice.QUALITY_VERY_HIGH -> 4
                        voice.quality >= Voice.QUALITY_HIGH -> 3
                        voice.quality >= Voice.QUALITY_NORMAL -> 2
                        else -> 1
                    }
                },
            )
            .firstOrNull()

        if (preferred != null) {
            engine.voice = preferred
            _voiceLabel.value = "Offline · ${preferred.name}"
        } else {
            engine.language = Locale.US
            _voiceLabel.value = "Offline · system default"
        }
        engine.setSpeechRate(0.95f)
        engine.setPitch(1.0f)
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

    fun speak(text: String) {
        if (!_isReady.value || text.isBlank()) return
        val cleaned = text
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            .replace(Regex("\\*\\*|__"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return
        ensureAudibleVolume()
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, "edge_ai_tts")
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
