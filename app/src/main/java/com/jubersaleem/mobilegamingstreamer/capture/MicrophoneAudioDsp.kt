package com.jubersaleem.mobilegamingstreamer.capture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device microphone voice chain ("studio voice").
 *
 * The platform AcousticEchoCanceler / NoiseSuppressor stay the first line of
 * defence whenever Android exposes them on the microphone session. This pure-JVM
 * chain then shapes the microphone signal before it is mixed with game audio:
 *
 *   HighPass (rumble) -> NoiseGate (fan/hiss) -> AutoGain (steady loudness)
 *     -> Compressor (shouts) -> PresenceEq (clarity)
 *
 * Every stage keeps its state across [process] calls, so frames must be fed in
 * capture order. Gain is always computed from the combined channels and applied
 * equally, which keeps the stereo image stable. Behaviour is fully
 * deterministic for a given input sequence, and is covered by JVM unit tests.
 */

private const val PCM_FULL_SCALE = 32768f

private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

private fun linearToDb(linear: Float): Float =
    (20.0 * kotlin.math.log10(linear.coerceAtLeast(1e-6f).toDouble())).toFloat()

/** One-pole smoothing coefficient for a time constant in milliseconds. */
private fun smoothingCoeff(timeMs: Float, sampleRate: Int): Float {
    require(timeMs > 0f)
    return (1.0 - exp(-1000.0 / (timeMs * sampleRate))).toFloat()
}

private fun readS16(data: ByteArray, index: Int): Float {
    val raw = (data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)
    return raw.toShort().toInt().toFloat()
}

private fun writeS16(data: ByteArray, index: Int, value: Float) {
    val sample = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    data[index] = (sample and 255).toByte()
    data[index + 1] = ((sample shr 8) and 255).toByte()
}

/** First-order high-pass that removes handling noise and low rumble. */
internal class MicrophoneHighPass(sampleRate: Int, private val channels: Int, cutoffHz: Float = 80f) {
    private val alpha = exp((-2.0 * PI * cutoffHz / sampleRate).toFloat())
    private val px = FloatArray(channels)
    private val py = FloatArray(channels)

    init {
        require(sampleRate > 0 && (channels == 1 || channels == 2))
        require(cutoffHz > 0f && cutoffHz < sampleRate / 2f)
    }

    fun process(input: ByteArray, offset: Int = 0, size: Int = input.size - offset): ByteArray {
        require(offset >= 0 && size >= 0 && offset + size <= input.size)
        val n = size - size % 2
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            val x = readS16(input, offset + i)
            val c = (i / 2) % channels
            val y = alpha * (py[c] + x - px[c])
            px[c] = x
            py[c] = y
            writeS16(out, i, y)
            i += 2
        }
        return out
    }
}

/**
 * Noise gate with hysteresis and smooth gain ramps.
 *
 * Conservative by design: thresholds sit well below quiet speech, the gate
 * opens in about a millisecond so word beginnings survive, and background
 * (fan, hiss, room tone) is pushed down gently instead of chopped to zero,
 * which avoids choppy "underwater" artefacts and preserves low acoustic
 * teammate voices rather than deleting them. When in doubt the gate preserves
 * speech over removing noise.
 */
internal class VoiceNoiseGate(
    sampleRate: Int,
    private val channels: Int,
    openDb: Float = -48f,
    closeDb: Float = -54f,
    attackMs: Float = 1f,
    releaseMs: Float = 250f,
    maxAttenuationDb: Float = 12f
) {
    private val openLevel = dbToLinear(openDb)
    private val closeLevel = dbToLinear(closeDb)
    private val floorGain = dbToLinear(-maxAttenuationDb)
    private val envAttack = smoothingCoeff(0.2f, sampleRate)
    private val envRelease = smoothingCoeff(60f, sampleRate)
    private val gainAttack = smoothingCoeff(attackMs, sampleRate)
    private val gainRelease = smoothingCoeff(releaseMs, sampleRate)
    private var envelope = 0f
    private var gain = floorGain
    private var open = false

    init {
        require(sampleRate > 0 && (channels == 1 || channels == 2))
        require(closeDb < openDb)
    }

    fun process(input: ByteArray, offset: Int = 0, size: Int = input.size - offset): ByteArray {
        require(offset >= 0 && size >= 0 && offset + size <= input.size)
        val stride = channels * 2
        val n = size - size % stride
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            var level = 0f
            for (c in 0 until channels) {
                level += abs(readS16(input, offset + i + c * 2))
            }
            level /= channels * PCM_FULL_SCALE
            envelope += (level - envelope) * (if (level > envelope) envAttack else envRelease)
            if (open && envelope < closeLevel) open = false
            else if (!open && envelope > openLevel) open = true
            val target = if (open) 1f else floorGain
            gain += (target - gain) * (if (target > gain) gainAttack else gainRelease)
            for (c in 0 until channels) {
                writeS16(out, i + c * 2, readS16(input, offset + i + c * 2) * gain)
            }
            i += stride
        }
        return out
    }
}

/**
 * Slow automatic gain control that keeps speech at a steady loudness.
 *
 * Quiet commentary is lifted towards [targetRmsDb] (capped at [maxGainDb] so
 * distant noise is never pumped up), while loud speech is eased down gently
 * for the compressor to finish. Adaptation is asymmetric — gain rises slowly
 * so pauses between words do not pump the background up, and falls faster so
 * sudden loud speech never blasts — and freezes below [freezeBelowDb] so room
 * silence is never boosted into audible hiss. Gain is always bounded, so long
 * sessions cannot drift.
 */
internal class VoiceAutoGain(
    sampleRate: Int,
    private val channels: Int,
    targetRmsDb: Float = -20f,
    maxGainDb: Float = 12f,
    minGainDb: Float = -3f,
    adaptUpMs: Float = 800f,
    adaptDownMs: Float = 300f,
    freezeBelowDb: Float = -48f
) {
    private val targetRms = dbToLinear(targetRmsDb)
    private val freezeLevel = dbToLinear(freezeBelowDb)
    private val maxGain = dbToLinear(maxGainDb)
    private val minGain = dbToLinear(minGainDb)
    private val window = smoothingCoeff(50f, sampleRate)
    private val adaptUp = smoothingCoeff(adaptUpMs, sampleRate)
    private val adaptDown = smoothingCoeff(adaptDownMs, sampleRate)
    private val smooth = smoothingCoeff(10f, sampleRate)
    private var meanSquare = 0f
    private var targetGain = 1f
    private var gain = 1f

    init {
        require(sampleRate > 0 && (channels == 1 || channels == 2))
    }

    fun process(input: ByteArray, offset: Int = 0, size: Int = input.size - offset): ByteArray {
        require(offset >= 0 && size >= 0 && offset + size <= input.size)
        val stride = channels * 2
        val n = size - size % stride
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            var power = 0f
            for (c in 0 until channels) {
                val x = readS16(input, offset + i + c * 2) / PCM_FULL_SCALE
                power += x * x
            }
            meanSquare += (power / channels - meanSquare) * window
            val rms = sqrt(meanSquare)
            if (rms > freezeLevel) {
                val desired = (targetRms / rms).coerceIn(minGain, maxGain)
                val rate = if (desired > targetGain) adaptUp else adaptDown
                targetGain += (desired - targetGain) * rate
            }
            gain += (targetGain - gain) * smooth
            for (c in 0 until channels) {
                writeS16(out, i + c * 2, readS16(input, offset + i + c * 2) * gain)
            }
            i += stride
        }
        return out
    }
}

/**
 * Soft-knee compressor that evens out loud laughs and shouts without pumping.
 * The gentle ratio and slow release preserve natural voice dynamics, the soft
 * attack lets transients through so speech stays lively, and quiet speech
 * below the knee passes through untouched.
 */
internal class VoiceCompressor(
    sampleRate: Int,
    private val channels: Int,
    private val thresholdDb: Float = -18f,
    private val ratio: Float = 2.5f,
    private val kneeDb: Float = 6f,
    attackMs: Float = 5f,
    releaseMs: Float = 150f,
    makeupDb: Float = 0f
) {
    private val attack = smoothingCoeff(attackMs, sampleRate)
    private val release = smoothingCoeff(releaseMs, sampleRate)
    private val makeup = dbToLinear(makeupDb)
    private var peak = 0f

    init {
        require(sampleRate > 0 && (channels == 1 || channels == 2))
        require(ratio >= 1f && kneeDb > 0f)
    }

    fun process(input: ByteArray, offset: Int = 0, size: Int = input.size - offset): ByteArray {
        require(offset >= 0 && size >= 0 && offset + size <= input.size)
        val stride = channels * 2
        val n = size - size % stride
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            var level = 0f
            for (c in 0 until channels) {
                level = maxOf(level, abs(readS16(input, offset + i + c * 2)))
            }
            level /= PCM_FULL_SCALE
            peak += (level - peak) * (if (level > peak) attack else release)
            val over = linearToDb(peak) - thresholdDb
            val halfKnee = kneeDb / 2f
            val reduction = when {
                over <= -halfKnee -> 0f
                over >= halfKnee -> over * (1f - 1f / ratio)
                else -> (1f - 1f / ratio) * (over + halfKnee) * (over + halfKnee) / (2f * kneeDb)
            }
            val gain = makeup * dbToLinear(-reduction)
            for (c in 0 until channels) {
                writeS16(out, i + c * 2, readS16(input, offset + i + c * 2) * gain)
            }
            i += stride
        }
        return out
    }
}

/**
 * Subtle presence bell (+2 dB around 3.2 kHz) that lifts speech intelligibility
 * over game audio without harshness, metallic coloration or sibilance emphasis.
 * Implemented as a standard peaking biquad (RBJ cookbook, Direct Form I,
 * per-channel state). It runs after the compressor, so boosted peaks always
 * stay inside PCM16 headroom.
 */
internal class VoicePresenceEq(
    sampleRate: Int,
    private val channels: Int,
    frequencyHz: Float = 3200f,
    gainDb: Float = 2f,
    q: Float = 0.7f
) {
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float
    private val x1 = FloatArray(channels)
    private val x2 = FloatArray(channels)
    private val y1 = FloatArray(channels)
    private val y2 = FloatArray(channels)

    init {
        require(sampleRate > 0 && (channels == 1 || channels == 2))
        require(frequencyHz > 0f && frequencyHz < sampleRate / 2f)
        require(q > 0f)
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val alpha = (sin(w0) / (2.0 * q)).toFloat()
        val cosW0 = cos(w0).toFloat()
        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cosW0) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha / a) / a0
    }

    fun process(input: ByteArray, offset: Int = 0, size: Int = input.size - offset): ByteArray {
        require(offset >= 0 && size >= 0 && offset + size <= input.size)
        val stride = channels * 2
        val n = size - size % stride
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            for (c in 0 until channels) {
                val x = readS16(input, offset + i + c * 2)
                val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                x2[c] = x1[c]
                x1[c] = x
                y2[c] = y1[c]
                y1[c] = y
                writeS16(out, i + c * 2, y)
            }
            i += stride
        }
        return out
    }
}

/** The full microphone voice chain applied before mixing with game audio. */
internal class VoiceOptimizer(sampleRate: Int, channels: Int, cutoffHz: Float = 80f) {
    private val highPass = MicrophoneHighPass(sampleRate, channels, cutoffHz)
    private val gate = VoiceNoiseGate(sampleRate, channels)
    private val autoGain = VoiceAutoGain(sampleRate, channels)
    private val compressor = VoiceCompressor(sampleRate, channels)
    private val presence = VoicePresenceEq(sampleRate, channels)

    fun process(input: ByteArray, offset: Int = 0, size: Int = input.size - offset): ByteArray {
        var stage = highPass.process(input, offset, size)
        stage = gate.process(stage)
        stage = autoGain.process(stage)
        stage = compressor.process(stage)
        return presence.process(stage)
    }
}

internal object Pcm16AudioMixer {
    fun mix(microphone: ByteArray, game: ByteArray?, microphoneGain: Float = 1f, gameGain: Float = 1f): ByteArray {
        val ms = microphone.size - microphone.size % 2
        val gs = game?.size?.let { it - it % 2 } ?: 0
        val out = ByteArray(maxOf(ms, gs))
        var i = 0
        while (i < out.size) {
            val m = if (i < ms) readS16(microphone, i) * microphoneGain else 0f
            val g = if (game != null && i < gs) readS16(game, i) * gameGain else 0f
            val sum = m + g
            val a = abs(sum)
            val lim = if (a <= 28000f) sum else (if (sum < 0f) -1f else 1f) * (28000f + (a - 28000f) * .22f).coerceAtMost(32400f)
            writeS16(out, i, lim)
            i += 2
        }
        return out
    }
}
