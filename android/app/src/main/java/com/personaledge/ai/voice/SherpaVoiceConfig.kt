package com.personaledge.ai.voice

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig

object SherpaVoiceConfig {
    const val STT_ASSET_DIR = "voice/stt/sherpa-onnx-streaming-zipformer-en-2023-06-26"
    const val TTS_ASSET_DIR = "voice/tts/vits-piper-en_US-lessac-medium"

    const val STT_LABEL = "Sherpa Zipformer (built-in)"
    const val TTS_LABEL = "Piper Lessac (built-in)"

    const val SAMPLE_RATE = 16_000

    fun sttRecognizerConfig(modelRoot: String): OnlineRecognizerConfig {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$modelRoot/encoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                decoder = "$modelRoot/decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                joiner = "$modelRoot/joiner-epoch-99-avg-1-chunk-16-left-128.onnx",
            ),
            tokens = "$modelRoot/tokens.txt",
            modelType = "zipformer2",
            numThreads = 4,
        )
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0.0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.8f, minUtteranceLength = 0.0f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 20.0f),
            ),
            enableEndpoint = true,
        )
    }

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
