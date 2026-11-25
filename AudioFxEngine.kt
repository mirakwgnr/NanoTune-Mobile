package com.nanotune.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class FxPreset(val label: String) {
    NORMAL("Normal"),
    LOFI_MUFFLE("Lo-Fi Muffle"),
    SLOWED_REVERB("Slowed + Reverb"),
    WARM_BASS("Warm Bass"),
    SPED_UP("Sped Up")
}

data class AudioFxState(
    val speed: Float = 1.0f,
    val pitchSemitones: Float = 0.0f,
    val linkPitchToSpeed: Boolean = true,
    val bassBoost: Float = 0.0f,
    val reverbMix: Float = 0.0f,
    val lofiEnabled: Boolean = false
) {
    val isAnyFxActive: Boolean
        get() = abs(speed - 1.0f) > 0.01f ||
            (!linkPitchToSpeed && abs(pitchSemitones) > 0.05f) ||
            bassBoost > 0.01f ||
            reverbMix > 0.01f ||
            lofiEnabled

    fun toPlaybackParameters(): PlaybackParameters {
        val effectivePitch = if (linkPitchToSpeed) {
            speed
        } else {
            2.0f.pow(pitchSemitones / 12.0f)
        }
        return PlaybackParameters(
            speed.coerceIn(0.60f, 1.40f),
            effectivePitch.coerceIn(0.42f, 2.00f)
        )
    }
}

object AudioFxStore {
    private val _state = MutableStateFlow(AudioFxState())
    val state: StateFlow<AudioFxState> = _state.asStateFlow()

    var onFxChangedListener: ((AudioFxState) -> Unit)? = null

    fun update(transform: (AudioFxState) -> AudioFxState) {
        val next = transform(_state.value).let { s ->
            s.copy(
                speed = s.speed.coerceIn(0.60f, 1.40f),
                pitchSemitones = s.pitchSemitones.coerceIn(-6.0f, 6.0f),
                bassBoost = s.bassBoost.coerceIn(0.0f, 1.0f),
                reverbMix = s.reverbMix.coerceIn(0.0f, 1.0f)
            )
        }
        _state.value = next
        onFxChangedListener?.invoke(next)
    }

    fun applyPreset(preset: FxPreset) {
        update { cur ->
            when (preset) {
                FxPreset.NORMAL -> AudioFxState(
                    speed = 1.0f,
                    pitchSemitones = 0.0f,
                    linkPitchToSpeed = true,
                    bassBoost = 0.0f,
                    reverbMix = 0.0f,
                    lofiEnabled = false
                )
                FxPreset.LOFI_MUFFLE -> cur.copy(
                    lofiEnabled = !cur.lofiEnabled
                )
                FxPreset.SLOWED_REVERB -> cur.copy(
                    speed = 0.84f,
                    pitchSemitones = 0.0f,
                    linkPitchToSpeed = true,
                    bassBoost = 0.0f,
                    reverbMix = 0.70f
                )
                FxPreset.WARM_BASS -> cur.copy(
                    speed = 1.0f,
                    pitchSemitones = 0.0f,
                    linkPitchToSpeed = true,
                    bassBoost = 0.65f,
                    reverbMix = 0.0f
                )
                FxPreset.SPED_UP -> cur.copy(
                    speed = 1.18f,
                    pitchSemitones = 0.0f,
                    linkPitchToSpeed = true,
                    bassBoost = 0.0f,
                    reverbMix = 0.30f
                )
            }
        }
    }
}

/**
 * Low-latency Media3 PCM AudioProcessor implementing:
 * - 155 Hz RBJ Low-Shelf Sub-Bass Boost (0-100%) with automatic headroom compensation & tanh soft limiter
 * - Recalibrated 10x gentler Stereo Schroeder/Freeverb Hall Reverb (0-100%)
 * - 2nd-order RBJ Low-Pass Biquad Lo-Fi Muffle filter (1400 Hz, Q = 0.707) with smooth crossfade
 * - Real-time Playback Speed and Pitch Shift are handled via ExoPlayer's SonicAudioProcessor
 *   using AudioFxState.toPlaybackParameters().
 */
@UnstableApi
class NanoTuneFxAudioProcessor : BaseAudioProcessor() {

    private var sampleRate = 44100
    private var channels = 2
    private var isFloatInput = false

    private var sBass = 0f
    private var sReverb = 0f
    private var sLofi = 0f

    // 155 Hz RBJ Low-Shelf Biquad
    private var shelfB0 = 1f
    private var shelfB1 = 0f
    private var shelfB2 = 0f
    private var shelfA1 = 0f
    private var shelfA2 = 0f
    private val shelfZ1 = FloatArray(6)
    private val shelfZ2 = FloatArray(6)
    private var lastShelfBass = -1f

    // 1400 Hz RBJ Low-Pass Biquad (Q = 0.707)
    private var lofiB0 = 1f
    private var lofiB1 = 0f
    private var lofiB2 = 0f
    private var lofiA1 = 0f
    private var lofiA2 = 0f
    private val lofiZ1 = FloatArray(6)
    private val lofiZ2 = FloatArray(6)

    // Stereo Schroeder/Freeverb delay lines (4 parallel damped combs + 2 series all-pass per channel)
    private val combDelaysL = intArrayOf(1116, 1188, 1277, 1356)
    private val combDelaysR = intArrayOf(1139, 1211, 1300, 1379)
    private val apDelaysL = intArrayOf(556, 441)
    private val apDelaysR = intArrayOf(579, 464)

    private var combBufL = Array(4) { FloatArray(1600) }
    private var combBufR = Array(4) { FloatArray(1600) }
    private var combIdxL = IntArray(4)
    private var combIdxR = IntArray(4)
    private var combStoreL = FloatArray(4)
    private var combStoreR = FloatArray(4)

    private var apBufL = Array(2) { FloatArray(700) }
    private var apBufR = Array(2) { FloatArray(700) }
    private var apIdxL = IntArray(2)
    private var apIdxR = IntArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channels = inputAudioFormat.channelCount.coerceIn(1, 6)
        isFloatInput = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        initFilterTables()
        return inputAudioFormat
    }

    private fun initFilterTables() {
        val ratio = sampleRate / 44100.0
        for (i in 0 until 4) {
            combBufL[i] = FloatArray((combDelaysL[i] * ratio).toInt().coerceAtLeast(64))
            combBufR[i] = FloatArray((combDelaysR[i] * ratio).toInt().coerceAtLeast(64))
        }
        for (i in 0 until 2) {
            apBufL[i] = FloatArray((apDelaysL[i] * ratio).toInt().coerceAtLeast(32))
            apBufR[i] = FloatArray((apDelaysR[i] * ratio).toInt().coerceAtLeast(32))
        }

        val w0 = 2.0 * PI * 1400.0 / sampleRate.toDouble()
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * 0.707)
        val a0 = 1.0 + alpha
        lofiB0 = (((1.0 - cosW0) * 0.5) / a0).toFloat()
        lofiB1 = ((1.0 - cosW0) / a0).toFloat()
        lofiB2 = lofiB0
        lofiA1 = ((-2.0 * cosW0) / a0).toFloat()
        lofiA2 = ((1.0 - alpha) / a0).toFloat()

        lastShelfBass = -1f
        updateBassShelfCoefficients(sBass)
    }

    private fun updateBassShelfCoefficients(bass: Float) {
        if (abs(bass - lastShelfBass) < 0.004f) return
        lastShelfBass = bass
        val dbGain = bass * 10.5
        val a = 10.0.pow(dbGain / 40.0)
        val w0 = 2.0 * PI * 155.0 / sampleRate.toDouble()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val slope = 0.85
        val alpha = (sinW0 / 2.0) * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        shelfB0 = ((a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0).toFloat()
        shelfB1 = ((2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0).toFloat()
        shelfB2 = ((a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0).toFloat()
        shelfA1 = ((-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0).toFloat()
        shelfA2 = (((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0).toFloat()
    }

    override fun onFlush() {
        shelfZ1.fill(0f)
        shelfZ2.fill(0f)
        lofiZ1.fill(0f)
        lofiZ2.fill(0f)
        for (i in 0 until 4) {
            combBufL[i].fill(0f)
            combBufR[i].fill(0f)
            combStoreL[i] = 0f
            combStoreR[i] = 0f
            combIdxL[i] = 0
            combIdxR[i] = 0
        }
        for (i in 0 until 2) {
            apBufL[i].fill(0f)
            apBufR[i].fill(0f)
            apIdxL[i] = 0
            apIdxR[i] = 0
        }
    }

    override fun onReset() {
        onFlush()
        sBass = 0f
        sReverb = 0f
        sLofi = 0f
        lastShelfBass = -1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val fx = AudioFxStore.state.value
        val targetBass = fx.bassBoost
        val targetRev = fx.reverbMix
        val targetLofi = if (fx.lofiEnabled) 1f else 0f

        if (!fx.isAnyFxActive &&
            abs(sBass - targetBass) < 0.001f &&
            abs(sReverb - targetRev) < 0.001f &&
            abs(sLofi - targetLofi) < 0.001f
        ) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val out = replaceOutputBuffer(remaining)
        val bytesPerSample = if (isFloatInput) 4 else 2
        val frameSize = bytesPerSample * channels
        val frameCount = remaining / frameSize
        val sampleTemp = FloatArray(channels)
        val coeffStep = 0.0025f

        for (f in 0 until frameCount) {
            sBass += (targetBass - sBass) * coeffStep
            sReverb += (targetRev - sReverb) * coeffStep
            sLofi += (targetLofi - sLofi) * coeffStep

            if (sBass > 0.002f) {
                updateBassShelfCoefficients(sBass)
            }

            for (ch in 0 until channels) {
                sampleTemp[ch] = if (isFloatInput) {
                    inputBuffer.float
                } else {
                    (inputBuffer.short / 32768f).coerceIn(-1f, 1f)
                }
            }

            if (sBass > 0.002f) {
                val headroom = 1.0f - 0.16f * sBass
                for (ch in 0 until channels) {
                    val x = sampleTemp[ch] * headroom
                    val y = shelfB0 * x + shelfZ1[ch]
                    shelfZ1[ch] = shelfB1 * x - shelfA1 * y + shelfZ2[ch]
                    shelfZ2[ch] = shelfB2 * x - shelfA2 * y
                    sampleTemp[ch] = tanh(y * 1.15f)
                }
            }

            if (sReverb > 0.002f && channels >= 2) {
                val dryL = sampleTemp[0]
                val dryR = sampleTemp[1]
                // Scaled down 10x for subtle studio depth matching Desktop Gold
                val monoIn = (dryL + dryR) * 0.0022f

                var sumCombL = 0f
                var sumCombR = 0f
                val feedback = 0.82f
                val damp = 0.28f
                val dampInv = 1.0f - damp

                for (c in 0 until 4) {
                    val bufL = combBufL[c]
                    val idxL = combIdxL[c]
                    val outL = bufL[idxL]
                    combStoreL[c] = outL * dampInv + combStoreL[c] * damp
                    bufL[idxL] = monoIn + combStoreL[c] * feedback
                    combIdxL[c] = (idxL + 1) % bufL.size
                    sumCombL += outL

                    val bufR = combBufR[c]
                    val idxR = combIdxR[c]
                    val outR = bufR[idxR]
                    combStoreR[c] = outR * dampInv + combStoreR[c] * damp
                    bufR[idxR] = monoIn + combStoreR[c] * feedback
                    combIdxR[c] = (idxR + 1) % bufR.size
                    sumCombR += outR
                }

                var apL = sumCombL * 0.25f
                var apR = sumCombR * 0.25f
                for (a in 0 until 2) {
                    val bL = apBufL[a]
                    val iL = apIdxL[a]
                    val dL = bL[iL]
                    bL[iL] = apL + dL * 0.5f
                    apIdxL[a] = (iL + 1) % bL.size
                    apL = dL - apL * 0.5f

                    val bR = apBufR[a]
                    val iR = apIdxR[a]
                    val dR = bR[iR]
                    bR[iR] = apR + dR * 0.5f
                    apIdxR[a] = (iR + 1) % bR.size
                    apR = dR - apR * 0.5f
                }

                // Scaled down 10x wet mix
                val wetGain = sReverb * 0.045f
                sampleTemp[0] = dryL + apL * wetGain
                sampleTemp[1] = dryR + apR * wetGain
            }

            if (sLofi > 0.002f) {
                for (ch in 0 until channels) {
                    val x = sampleTemp[ch]
                    val y = lofiB0 * x + lofiZ1[ch]
                    lofiZ1[ch] = lofiB1 * x - lofiA1 * y + lofiZ2[ch]
                    lofiZ2[ch] = lofiB2 * x - lofiA2 * y
                    sampleTemp[ch] = x * (1f - sLofi) + y * sLofi
                }
            }

            for (ch in 0 until channels) {
                val clamped = sampleTemp[ch].coerceIn(-1.0f, 1.0f)
                if (isFloatInput) {
                    out.putFloat(clamped)
                } else {
                    out.putShort((clamped * 32767f).toInt().toShort())
                }
            }
        }

        out.flip()
    }
}