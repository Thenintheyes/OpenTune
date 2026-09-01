/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 *
 * Procesador de audio PCM para fade-in/fade-out a nivel de pipeline de ExoPlayer.
 * Trabaja de forma ortogonal a CrossfadeAudio: este aplica el fundido dentro de
 * un único stream de audio (inicio/fin de pista), mientras que CrossfadeAudio
 * mezcla dos players simultáneos durante la transición entre canciones.
 *
 * Arquitectura del buffer:
 *   - tailBuffer (ring buffer circular): retiene los últimos N bytes del stream
 *     entrante equivalentes a crossfadeDurationMs, para poder aplicarles
 *     fade-out al detectar el fin del stream.
 *   - outBuffer  (ring buffer circular): cola de bytes ya procesados listos
 *     para entregar a través de getOutput().
 *
 * Flujo de datos:
 *   queueInput() → appendInputToTail() + drainTailToArray() → applyFadeIn/Out → enqueueOutput()
 *   getOutput()  → dequeueOutputToByteBuffer()
 */

package com.arturo254.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.arturo254.opentune.constants.CrossfadeType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

@UnstableApi
class CrossfadeAudioProcessor : AudioProcessor {

    // ── Formato de audio ──────────────────────────────────────────────────────

    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET

    // ── Duración y estilo de crossfade ────────────────────────────────────────

    /** Duración configurada externamente. Aplica en el próximo flush(). */
    @Volatile
    var crossfadeDurationMs: Int = 0
        set(value) {
            field = value
            pendingCrossfadeDurationMs = value
        }

    /** Estilo de curva configurado externamente. Se usa en el próximo frame. */
    @Volatile
    var crossfadeType: CrossfadeType = CrossfadeType.DEFAULT

    /**
     * Si es true, se desactivan los fundidos por stream individual (fade-in del
     * stream actual / fade-out del stream actual). Lo activa CrossfadeAudio
     * durante la transición dual-player (outgoing + incoming), ya que en ese
     * caso el fading lo controla el propio CrossfadeAudio mediante volúmenes.
     * Los fade-in iniciales (primer stream) y fade-out finales (último stream)
     * siguen ejecutándose aunque esté activado.
     */
    @Volatile
    var streamFadesBypassed: Boolean = false

    @Volatile
    private var pendingCrossfadeDurationMs: Int = 0

    private var appliedCrossfadeDurationMs: Int = 0
    private var crossfadeFrames: Int = 0
    private var bytesPerFrame: Int = 0

    // ── Estado de fade ────────────────────────────────────────────────────────

    private var isEnding: Boolean = false
    private var shouldFadeInThisStream: Boolean = false
    private var shouldFadeInNextStream: Boolean = false
    private var framesOutputInStream: Long = 0L
    private var skipFadeInOnNextStream: Boolean = false

    // ── Ring buffer: cola de tail ─────────────────────────────────────────────

    private var tailBufferBytes: ByteArray = ByteArray(0)
    private var tailCapacityBytes: Int = 0
    private var tailStartIndex: Int = 0
    private var tailSizeBytes: Int = 0

    // ── Ring buffer: cola de salida ───────────────────────────────────────────

    private var outBufferBytes: ByteArray = ByteArray(0)
    private var outCapacityBytes: Int = 0
    private var outStartIndex: Int = 0
    private var outSizeBytes: Int = 0

    // ── Scratch y buffer de salida de ByteBuffer ──────────────────────────────

    private var scratch: ByteArray = ByteArray(0)
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    // ── AudioProcessor API ────────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        bytesPerFrame = inputAudioFormat.channelCount * 2
        applyCrossfadeDurationIfNeeded(force = true)
        return outputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingCrossfadeDurationMs > 0 && inputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputAudioFormat == AudioFormat.NOT_SET) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        applyCrossfadeDurationIfNeeded(force = false)

        val incomingBytes = inputBuffer.remaining()
        if (incomingBytes <= 0) return

        // Sin crossfade configurado: pass-through directo
        if (crossfadeFrames <= 0 || bytesPerFrame <= 0 || tailCapacityBytes <= 0) {
            ensureScratchCapacity(incomingBytes)
            inputBuffer.get(scratch, 0, incomingBytes)
            enqueueOutput(scratch, 0, incomingBytes)
            return
        }

        if (shouldFadeInNextStream) {
            shouldFadeInThisStream = true
            shouldFadeInNextStream = false
        }

        val bytesToOutput = computeBytesToOutput(incomingBytes)
        val alignedBytesToOutput = bytesToOutput - (bytesToOutput % bytesPerFrame)

        if (alignedBytesToOutput > 0) {
            ensureScratchCapacity(alignedBytesToOutput)
            drainTailToArray(scratch, 0, alignedBytesToOutput)

            if (shouldFadeInThisStream) {
                applyFadeInPcm16(
                    data = scratch,
                    offset = 0,
                    length = alignedBytesToOutput,
                    startFrameIndex = framesOutputInStream,
                )
            }

            framesOutputInStream += (alignedBytesToOutput / bytesPerFrame).toLong()

            if (shouldFadeInThisStream && framesOutputInStream >= crossfadeFrames.toLong()) {
                shouldFadeInThisStream = false
            }

            enqueueOutput(scratch, 0, alignedBytesToOutput)
        }

        appendInputToTail(inputBuffer)
    }

    override fun getOutput(): ByteBuffer {
        applyCrossfadeDurationIfNeeded(force = false)

        if (outSizeBytes > 0) {
            return dequeueOutputToByteBuffer(outSizeBytes)
        }

        if (!isEnding) return AudioProcessor.EMPTY_BUFFER

        // Fin del stream: aplicar fade-out al contenido del tail
        if (crossfadeFrames <= 0 || bytesPerFrame <= 0 || tailSizeBytes <= 0) {
            shouldFadeInThisStream = false
            shouldFadeInNextStream = false
            tailStartIndex = 0
            tailSizeBytes = 0
            return AudioProcessor.EMPTY_BUFFER
        }

        val alignedTailBytes = tailSizeBytes - (tailSizeBytes % bytesPerFrame)
        if (alignedTailBytes <= 0) {
            shouldFadeInThisStream = false
            shouldFadeInNextStream = false
            tailStartIndex = 0
            tailSizeBytes = 0
            return AudioProcessor.EMPTY_BUFFER
        }

        ensureScratchCapacity(alignedTailBytes)
        drainTailToArray(scratch, 0, alignedTailBytes)

        val tailFrames = alignedTailBytes / bytesPerFrame
        applyFadeOutPcm16(
            data = scratch,
            offset = 0,
            frameCount = tailFrames,
            startFrameIndex = framesOutputInStream,
        )
        framesOutputInStream += tailFrames.toLong()

        // Señalizar que el stream siguiente debe hacer fade-in
        shouldFadeInNextStream = true
        enqueueOutput(scratch, 0, alignedTailBytes)

        return dequeueOutputToByteBuffer(outSizeBytes)
    }

    override fun isEnded(): Boolean =
        isEnding && outSizeBytes == 0 && tailSizeBytes == 0

    /**
     * En la próxima transición de stream (flush + nuevo stream) se marca que
     * el fade-in inicial no se aplique — usado para evitar un fade-in PCM
     * espurio después del handoff overlap→main.
     */
    fun skipNextFadeIn() {
        skipFadeInOnNextStream = true
    }

    override fun flush() {
        val preserveFadeIn = isEnding && shouldFadeInNextStream && !skipFadeInOnNextStream
        framesOutputInStream = if (skipFadeInOnNextStream) {
            crossfadeFrames.toLong() + 1L
        } else {
            0L
        }
        skipFadeInOnNextStream = false
        isEnding = false
        shouldFadeInThisStream = false
        shouldFadeInNextStream = preserveFadeIn
        tailStartIndex = 0
        tailSizeBytes = 0
        outStartIndex = 0
        outSizeBytes = 0
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        pendingCrossfadeDurationMs = 0
        appliedCrossfadeDurationMs = 0
        crossfadeDurationMs = 0
        crossfadeFrames = 0
        bytesPerFrame = 0
        shouldFadeInNextStream = false
        tailBufferBytes = ByteArray(0)
        tailCapacityBytes = 0
        tailStartIndex = 0
        tailSizeBytes = 0
        outBufferBytes = ByteArray(0)
        outCapacityBytes = 0
        outStartIndex = 0
        outSizeBytes = 0
        scratch = ByteArray(0)
        outputBuffer = AudioProcessor.EMPTY_BUFFER
    }

    override fun queueEndOfStream() {
        isEnding = true
    }

    // ── Gestión de duración ───────────────────────────────────────────────────

    private fun applyCrossfadeDurationIfNeeded(force: Boolean) {
        val targetMs = pendingCrossfadeDurationMs
        if (!force && targetMs == appliedCrossfadeDurationMs) return

        appliedCrossfadeDurationMs = targetMs

        val newCrossfadeFrames =
            if (inputAudioFormat != AudioFormat.NOT_SET && bytesPerFrame > 0 && targetMs > 0) {
                (inputAudioFormat.sampleRate * targetMs) / 1000
            } else {
                0
            }
        crossfadeFrames = newCrossfadeFrames

        val newCapacityBytes =
            if (newCrossfadeFrames > 0 && bytesPerFrame > 0) newCrossfadeFrames * bytesPerFrame else 0

        if (newCapacityBytes == tailCapacityBytes) return

        if (newCapacityBytes <= 0) {
            tailBufferBytes = ByteArray(0)
            tailCapacityBytes = 0
            tailStartIndex = 0
            tailSizeBytes = 0
            shouldFadeInThisStream = false
            shouldFadeInNextStream = false
            return
        }

        // Redimensionar el tail preservando los bytes más recientes
        val newBuffer = ByteArray(newCapacityBytes)
        val bytesToCopy = min(tailSizeBytes, newCapacityBytes)

        if (bytesToCopy > 0 && tailCapacityBytes > 0) {
            val oldCapacity = tailCapacityBytes
            val tailEndExclusive = (tailStartIndex + tailSizeBytes) % oldCapacity
            val copyStartIndex =
                ((tailEndExclusive - bytesToCopy) % oldCapacity + oldCapacity) % oldCapacity

            val firstChunk = min(bytesToCopy, oldCapacity - copyStartIndex)
            System.arraycopy(tailBufferBytes, copyStartIndex, newBuffer, 0, firstChunk)
            val remaining = bytesToCopy - firstChunk
            if (remaining > 0) System.arraycopy(
                tailBufferBytes,
                0,
                newBuffer,
                firstChunk,
                remaining
            )
        }

        tailBufferBytes = newBuffer
        tailCapacityBytes = newCapacityBytes
        tailStartIndex = 0
        tailSizeBytes = bytesToCopy
    }

    // ── Ring buffer: tail ─────────────────────────────────────────────────────

    private fun computeBytesToOutput(incomingBytes: Int): Int {
        val total = tailSizeBytes + incomingBytes
        return (total - tailCapacityBytes).coerceAtLeast(0)
    }

    private fun drainTailToArray(target: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val capacity = tailCapacityBytes
        val firstChunk = min(length, capacity - tailStartIndex)
        System.arraycopy(tailBufferBytes, tailStartIndex, target, offset, firstChunk)
        val remaining = length - firstChunk
        if (remaining > 0) System.arraycopy(
            tailBufferBytes,
            0,
            target,
            offset + firstChunk,
            remaining
        )
        tailStartIndex = (tailStartIndex + length) % capacity
        tailSizeBytes -= length
    }

    private fun appendInputToTail(inputBuffer: ByteBuffer) {
        val capacity = tailCapacityBytes
        if (capacity <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val bytesToAppend = inputBuffer.remaining()
        if (bytesToAppend <= 0) return

        var endIndex = (tailStartIndex + tailSizeBytes) % capacity
        var remaining = bytesToAppend
        while (remaining > 0) {
            val chunk = min(remaining, capacity - endIndex)
            inputBuffer.get(tailBufferBytes, endIndex, chunk)
            tailSizeBytes += chunk
            remaining -= chunk
            endIndex = (endIndex + chunk) % capacity
        }
    }

    // ── Ring buffer: salida ───────────────────────────────────────────────────

    private fun enqueueOutput(source: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensureOutputCapacity(length)
        val capacity = outCapacityBytes
        var endIndex = (outStartIndex + outSizeBytes) % capacity
        var remaining = length
        var srcOffset = offset
        while (remaining > 0) {
            val chunk = min(remaining, capacity - endIndex)
            System.arraycopy(source, srcOffset, outBufferBytes, endIndex, chunk)
            srcOffset += chunk
            remaining -= chunk
            endIndex = (endIndex + chunk) % capacity
        }
        outSizeBytes += length
    }

    private fun dequeueOutputToByteBuffer(maxBytes: Int): ByteBuffer {
        val bytesToRead = min(outSizeBytes, maxBytes).coerceAtLeast(0)
        if (bytesToRead <= 0) return AudioProcessor.EMPTY_BUFFER

        val out = replaceOutputBuffer(bytesToRead)
        val capacity = outCapacityBytes
        val firstChunk = min(bytesToRead, capacity - outStartIndex)
        out.put(outBufferBytes, outStartIndex, firstChunk)
        val remaining = bytesToRead - firstChunk
        if (remaining > 0) out.put(outBufferBytes, 0, remaining)
        out.flip()

        outStartIndex = (outStartIndex + bytesToRead) % capacity
        outSizeBytes -= bytesToRead
        if (outSizeBytes == 0) outStartIndex = 0

        return out
    }

    private fun ensureOutputCapacity(additionalBytes: Int) {
        val required = outSizeBytes + additionalBytes
        if (outCapacityBytes >= required) return

        val newCapacity = when {
            outCapacityBytes <= 0 -> maxOf(16_384, required)
            else -> maxOf(outCapacityBytes * 2, required)
        }

        val newBuffer = ByteArray(newCapacity)
        if (outSizeBytes > 0) {
            val firstChunk = min(outSizeBytes, outCapacityBytes - outStartIndex)
            System.arraycopy(outBufferBytes, outStartIndex, newBuffer, 0, firstChunk)
            val remaining = outSizeBytes - firstChunk
            if (remaining > 0) System.arraycopy(outBufferBytes, 0, newBuffer, firstChunk, remaining)
        }

        outBufferBytes = newBuffer
        outCapacityBytes = newCapacity
        outStartIndex = 0
    }

    // ── DSP: curvas tipificadas + fade-in/out sobre PCM 16-bit ────────────────

    /**
     * Devuelve `Pair(outgoingGain, incomingGain)` para `t ∈ [0, 1]` según el
     * [crossfadeType] actual. Implementación simétrica a
     * [CrossfadeAudio.computeCrossfadeGains] para mantener el mismo carácter
     * entre los dos sistemas de fading.
     */
    private fun computeCrossfadeGains(t: Float): Pair<Float, Float> {
        val x = t.coerceIn(0f, 1f)
        val type = crossfadeType
        val outgoingGain: Float
        val incomingGain: Float
        when (type) {
            CrossfadeType.EQUAL_POWER -> {
                val angle = x * Math.PI.toFloat() * 0.5f
                outgoingGain = cos(angle)
                incomingGain = sin(angle)
            }
            CrossfadeType.LINEAR -> {
                outgoingGain = 1f - x
                incomingGain = x
            }
            CrossfadeType.LOGARITHMIC -> {
                val epsilon = 1e-6f
                outgoingGain = ln((1f - x) * (1f - epsilon) + epsilon) / ln(epsilon)
                incomingGain = ln(x * (1f - epsilon) + epsilon) / ln(epsilon)
            }
            CrossfadeType.EXPONENTIAL -> {
                outgoingGain = (exp(-x * 4f) - exp(-4f)) / (1f - exp(-4f))
                incomingGain = (exp((x - 1f) * 4f) - exp(-4f)) / (1f - exp(-4f))
            }
            CrossfadeType.SMOOTHSTEP -> {
                val s = x * x * (3f - 2f * x)
                outgoingGain = 1f - s
                incomingGain = s
            }
            CrossfadeType.SMOOTHERSTEP -> {
                val s = x * x * x * (x * (x * 6f - 15f) + 10f)
                outgoingGain = 1f - s
                incomingGain = s
            }
            CrossfadeType.CONSTANT_GAIN -> {
                outgoingGain = 1f - x
                incomingGain = x
            }
            CrossfadeType.FAST_START -> {
                val s = 1f - (1f - x).pow(3f)
                outgoingGain = 1f - s
                incomingGain = s
            }
            CrossfadeType.SLOW_START -> {
                val s = x * x * x
                outgoingGain = 1f - s
                incomingGain = s
            }
            CrossfadeType.WAVE_MIX -> {
                val phase = x * Math.PI.toFloat()
                val shift = (sin(phase) * 0.18f)
                val inT = (x + shift).coerceIn(0f, 1f)
                val outT = (x - shift).coerceIn(0f, 1f)
                val inAngle = inT * Math.PI.toFloat() * 0.5f
                val outAngle = outT * Math.PI.toFloat() * 0.5f
                outgoingGain = cos(outAngle)
                incomingGain = sin(inAngle)
            }
            CrossfadeType.TRIANGLE -> {
                val tri = if (x <= 0.5f) 2f * x else 2f * (1f - x)
                outgoingGain = (1f - x) * (0.5f + 0.5f * tri)
                incomingGain = x * (0.5f + 0.5f * tri)
            }
            CrossfadeType.DIPPED -> {
                val dip = 1f - 0.4f * sin(x * Math.PI.toFloat())
                val eqOut = cos(x * Math.PI.toFloat() * 0.5f)
                val eqIn = sin(x * Math.PI.toFloat() * 0.5f)
                outgoingGain = (eqOut * dip).coerceIn(0f, 1f)
                incomingGain = (eqIn * dip).coerceIn(0f, 1f)
            }
        }
        val outG = outgoingGain.coerceIn(0f, 1f)
        val inG = incomingGain.coerceIn(0f, 1f)
        val total = outG + inG
        return if (type == CrossfadeType.DIPPED || total == 0f) {
            outG to inG
        } else {
            val norm = (1.41f / total).coerceAtMost(1.02f)
            (outG * norm).coerceIn(0f, 1.05f) to (inG * norm).coerceIn(0f, 1.05f)
        }
    }

    /**
     * Aplica un fade-in (curva tipificada) a [length] bytes de [data] comenzando en [offset].
     * El gain va de 0 → 1 a lo largo de [crossfadeFrames] frames totales,
     * contando desde [startFrameIndex].
     * Si [streamFadesBypassed] está activado, este método es no-op (el fading
     * lo está gestionando CrossfadeAudio vía volúmenes).
     */
    private fun applyFadeInPcm16(
        data: ByteArray,
        offset: Int,
        length: Int,
        startFrameIndex: Long,
    ) {
        if (streamFadesBypassed) return
        if (crossfadeFrames <= 0 || bytesPerFrame <= 0) return
        val frames = length / bytesPerFrame
        val totalFrames = crossfadeFrames.toFloat()
        var i = 0
        while (i < frames) {
            val globalFrame = startFrameIndex + i
            val gain = if (globalFrame < crossfadeFrames.toLong()) {
                computeCrossfadeGains(globalFrame.toFloat() / totalFrames).second
            } else {
                1f
            }
            scaleFramePcm16(data, offset + i * bytesPerFrame, gain)
            i++
        }
    }

    /**
     * Aplica un fade-out (curva tipificada) a [frameCount] frames de [data] comenzando en [offset].
     * Si [shouldFadeInThisStream] está activo, combina fade-in simultáneo para el
     * stream entrante (crossfade a nivel PCM en el mismo buffer).
     * Si [streamFadesBypassed] está activado, el fade-in/fade-out por stream
     * no se aplica (salvo el fade-out final: al alcanzar queueEndOfStream con
     * frameCount > 0 se mantiene por compatibilidad con búsquedas y seek).
     */
    private fun applyFadeOutPcm16(
        data: ByteArray,
        offset: Int,
        frameCount: Int,
        startFrameIndex: Long,
    ) {
        if (frameCount <= 0 || bytesPerFrame <= 0) return

        val denom = (frameCount - 1).coerceAtLeast(1).toFloat()
        val incomingTotalFrames = crossfadeFrames.toFloat()
        var i = 0
        while (i < frameCount) {
            val t = if (frameCount <= 1) 1f else (i.toFloat() / denom)
            val fadeOutGain =
                if (streamFadesBypassed) 1f
                else computeCrossfadeGains(t).first

            val globalFrame = startFrameIndex + i
            val fadeInGain = if (!streamFadesBypassed &&
                shouldFadeInThisStream && crossfadeFrames > 0 &&
                globalFrame < crossfadeFrames.toLong()
            ) {
                computeCrossfadeGains(globalFrame.toFloat() / incomingTotalFrames).second
            } else {
                1f
            }

            scaleFramePcm16(data, offset + i * bytesPerFrame, fadeInGain * fadeOutGain)
            i++
        }
    }

    /**
     * Escala un frame PCM 16-bit little-endian por [gain].
     * Cubre todos los canales del frame ([bytesPerFrame] bytes).
     */
    private fun scaleFramePcm16(data: ByteArray, frameOffset: Int, gain: Float) {
        if (gain == 1f) return
        var byteIndex = frameOffset
        val frameEnd = frameOffset + bytesPerFrame
        while (byteIndex < frameEnd) {
            val lo = data[byteIndex].toInt() and 0xFF
            val hi = data[byteIndex + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val scaled = (sample * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            data[byteIndex] = (scaled and 0xFF).toByte()
            data[byteIndex + 1] = ((scaled shr 8) and 0xFF).toByte()
            byteIndex += 2
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private fun ensureScratchCapacity(size: Int) {
        if (scratch.size < size) scratch = ByteArray(size)
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        return if (outputBuffer.capacity() < size) {
            ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                .also { outputBuffer = it }
        } else {
            outputBuffer.clear()
            outputBuffer
        }
    }
}