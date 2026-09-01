/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 *
 * Crossfade improvements ported from ArchiveTune (github.com/koiverse):
 *  - Equal-power volume curve (sin/cos) — elimina el "dip" perceptual en el punto medio
 *  - Gapless album skip — no hace crossfade entre pistas del mismo álbum
 *  - Buffer check antes de iniciar — evita arranque con rebuffering
 */

package com.arturo254.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arturo254.opentune.constants.CrossfadeType
import com.arturo254.opentune.db.MusicDatabase
import com.arturo254.opentune.extensions.metadata
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal class CrossfadeAudio(
    private val player: ExoPlayer,
    private val database: MusicDatabase,
    private val crossfadeDurationMs: MutableStateFlow<Int>,
    private val crossfadeType: MutableStateFlow<CrossfadeType>,
    private val playbackFadeFactor: MutableStateFlow<Float>,
    private val playerVolume: MutableStateFlow<Float>,
    private val audioFocusVolumeFactor: MutableStateFlow<Float>,
    private val audioNormalizationEnabled: MutableStateFlow<Boolean>,
    private val maxSafeGainFactor: Float,
    private val sharedNormalizeFactor: StateFlow<Float>,
    private val mainCrossfadeProcessor: CrossfadeAudioProcessor? = null,
    private val overlapCrossfadeProcessor: CrossfadeAudioProcessor? = null,
    private val overlapPlayerFactory: () -> ExoPlayer,
    private val onCrossfadeStart: (MediaItem) -> Unit = {},
    private val onHandoffNormalizeSnap: (targetFactor: Float, postHandoffRampMs: Int) -> Unit = { _, _ -> },
    private val streamFadesBypassController: ((bypassed: Boolean, applyNextStartOnly: Boolean) -> Unit)? = null,
    private val onCrossfadeAndHandoffAllFinished: () -> Unit = {},
) {

    // ── Curvas de crossfade DSP ────────────────────────────────────────────────

    /**
     * Calcula los factores de volumen para el fundido según [CrossfadeType].
     * Devuelve un par (outgoingGain, incomingGain) ambos en rango [0, 1].
     *
     * @param t Progreso normalizado de la transición [0..1]
     */
    private fun computeCrossfadeGains(t: Float, type: CrossfadeType): Pair<Float, Float> {
        val clampedT = t.coerceIn(0f, 1f)
        return when (type) {
            CrossfadeType.EQUAL_POWER -> {
                // sin/cos equal-power: sin²θ + cos²θ = 1 → potencia constante
                val theta = clampedT * (PI / 2.0)
                cos(theta).toFloat() to sin(theta).toFloat()
            }

            CrossfadeType.LINEAR -> {
                (1f - clampedT) to clampedT
            }

            CrossfadeType.LOGARITHMIC -> {
                // Logarítmico: fade-in suave, fade-out rápido
                val eps = 0.001f
                val out = (ln((1f - clampedT) * (1f - eps) + eps) / ln(eps)).coerceIn(0f, 1f)
                val inc = (ln(clampedT * (1f - eps) + eps) / ln(eps)).coerceIn(0f, 1f)
                (1f - out) to inc
            }

            CrossfadeType.EXPONENTIAL -> {
                // Exponencial: fade-in rápido, fade-out suave
                val k = 3f
                val expIn = (1f - Math.exp(-k * clampedT.toDouble()).toFloat() /
                    (1f - Math.exp(-k.toDouble()).toFloat())).coerceIn(0f, 1f)
                val expOut = (1f - Math.exp(-k * (1 - clampedT).toDouble()).toFloat() /
                    (1f - Math.exp(-k.toDouble()).toFloat())).coerceIn(0f, 1f)
                (1f - expOut) to expIn
            }

            CrossfadeType.SMOOTHSTEP -> {
                // smoothstep(t) = 3t² - 2t³
                val s = clampedT * clampedT * (3f - 2f * clampedT)
                (1f - s) to s
            }

            CrossfadeType.SMOOTHERSTEP -> {
                // smootherstep(t) = 6t⁵ - 15t⁴ + 10t³
                val s = clampedT * clampedT * clampedT * (
                    clampedT * (clampedT * 6f - 15f) + 10f
                )
                (1f - s) to s
            }

            CrossfadeType.CONSTANT_GAIN -> {
                // Ganancia constante: suma lineal de amplitudes (no potencia)
                // Para mantener suma ≈ 1 usamos raíces
                val a = sqrt(1f - clampedT)
                val b = sqrt(clampedT)
                a to b
            }

            CrossfadeType.FAST_START -> {
                // incoming aparece muy rápido al principio
                val inc = 1f - (1f - clampedT).pow(3)
                val out = (1f - clampedT).pow(1.5f)
                out to inc
            }

            CrossfadeType.SLOW_START -> {
                // incoming aparece muy suave al principio
                val inc = clampedT.pow(3)
                val out = 1f - (1f - clampedT).pow(0.35f)
                (1f - out) to inc
            }

            CrossfadeType.WAVE_MIX -> {
                // Mezcla de ondas con fase desplazada
                val theta = clampedT * PI
                val out = ((1.0 + cos(theta)) * 0.5).toFloat()
                val inc = ((1.0 - cos(theta)) * 0.5).toFloat()
                out to inc
            }

            CrossfadeType.TRIANGLE -> {
                // Triangular / tiesto: crossover asimétrico lineal
                // outgoing empieza a bajar antes; incoming llega a 1 antes
                val out = when {
                    clampedT < 0.5f -> 1f
                    else -> 2f * (1f - clampedT)
                }
                val inc = when {
                    clampedT < 0.5f -> clampedT * 2f
                    else -> 1f
                }
                out to inc
            }

            CrossfadeType.DIPPED -> {
                // Hundimiento en medio: ambos bajan brevemente → enfatiza el corte
                val dip = when {
                    clampedT < 0.5f -> 1f - (sin(clampedT * PI).toFloat() * 0.45f)
                    else -> 1f - (sin((1f - clampedT) * PI).toFloat() * 0.45f)
                }
                val rawOut = 1f - clampedT
                val rawIn = clampedT
                (rawOut * dip) to (rawIn * dip)
            }
        }
    }
    // ── Estado del loop ───────────────────────────────────────────────────────

    private var loopJob: Job? = null

    // ── Estado del overlap player ─────────────────────────────────────────────

    private var overlapPlayer: ExoPlayer? = null
    private var overlapPrimedIndex: Int = C.INDEX_UNSET
    private var overlapPrimedMediaId: String? = null
    private var crossfadeActive = false
    private var crossfadeTargetIndex: Int = C.INDEX_UNSET
    private var crossfadeTargetMediaId: String? = null
    private var crossfadeStartElapsedMs: Long = 0L
    private var crossfadeActiveDurationMs: Int = 0
    private var overlapNormalizeFactor: Float = 1f

    // ── Estado del handoff ────────────────────────────────────────────────────

    private var handoffActive = false
    private var handoffStartElapsedMs: Long = 0L
    private var handoffDurationMs: Int = 0
    private var handoffTargetPositionMs: Long = 0L
    private var handoffLastSyncSeekElapsedMs: Long = 0L
    private var handoffSeekIssued = false
    private var handoffRampStarted = false
    private var handoffMainStartFade: Float = 0f
    private var handoffOverlapStartFade: Float = 0f
    private var postHandoffOverlapDrainUntil: Long = 0L

    // Constantes de handoff
    private val handoffReseekMinIntervalMs = 180L
    private val handoffDriftCorrectionThresholdMs = 220L
    private val handoffRampStartDriftToleranceMs = 120L
    private val handoffTimeoutMs = 5000L
    private val handoffStandardDurationMs = 550
    private val postHandoffOverlapDrainMs = 120L

    // ── Buffer mínimo antes de arrancar el crossfade ──────────────────────────

    /** Cuánto buffer (ms) necesita el overlap player antes de permitir beginOverlapCrossfade.
     *  Se calcula como fadeMs + 2s, acotado entre 3s y 10s. */
    private fun requiredStartBufferMs(fadeMs: Int): Long =
        (fadeMs.toLong() + 2_000L).coerceIn(3_000L, 10_000L)

    // ── API pública ───────────────────────────────────────────────────────────

    fun isCrossfading(): Boolean = crossfadeActive

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        handleMediaItemTransition(mediaItem, reason)
    }

    fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            stop(resetMainFade = true)
        }
    }

    fun stop(resetMainFade: Boolean) {
        stopOverlapCrossfade(resetMainFade = resetMainFade)
    }

    fun release() {
        loopJob?.cancel()
        loopJob = null
        stopOverlapCrossfade(resetMainFade = true)
        runCatching { overlapPlayer?.release() }
        overlapPlayer = null
        setStreamFadesBypassed(false)
    }

    // ── Loop principal ────────────────────────────────────────────────────────

    private suspend fun runLoop() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val fadeMs = crossfadeDurationMs.value
            val now = android.os.SystemClock.elapsedRealtime()

            // Fase post-handoff: dejar de drenar overlap sin crossfadeActive ni handoffActive
            if (postHandoffOverlapDrainUntil > 0L) {
                if (now >= postHandoffOverlapDrainUntil) {
                    val overlap = overlapPlayer
                    if (overlap != null) {
                        runCatching {
                            overlap.volume = 0f
                            overlap.stop()
                            overlap.clearMediaItems()
                        }
                    }
                    postHandoffOverlapDrainUntil = 0L
                    onCrossfadeAndHandoffAllFinished()
                } else {
                    delay(25)
                    continue
                }
            }

            if (fadeMs <= 0) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(250)
                continue
            }

            if (!player.playWhenReady) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            // Durante el handoff solo actualizar volúmenes
            if (handoffActive) {
                updateVolumes()
                delay(20)
                continue
            }

            if (!crossfadeActive && (player.playbackState != Player.STATE_READY || !player.isPlaying)) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            val durationMs = player.duration
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val nextIndex = player.nextMediaItemIndex

            // No crossfade en repeat-one
            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            if (!crossfadeActive && (nextIndex == C.INDEX_UNSET || durationMs <= 0 || durationMs == C.TIME_UNSET)) {
                stopOverlapCrossfade(resetMainFade = true)
                delay(150)
                continue
            }

            // ── Skip gapless para álbumes ─────────────────────────────────────
            // Si la transición es entre pistas del mismo álbum y el crossfade no está
            // activo todavía, lo omitimos para respetar el gapless del álbum.
            if (!crossfadeActive && nextIndex != C.INDEX_UNSET) {
                val currentItem =
                    runCatching { player.getMediaItemAt(player.currentMediaItemIndex) }.getOrNull()
                val nextItem = runCatching { player.getMediaItemAt(nextIndex) }.getOrNull()
                if (currentItem != null && nextItem != null && isGaplessAlbumTransition(
                        currentItem,
                        nextItem
                    )
                ) {
                    unprimeOverlap()
                    delay(150)
                    continue
                }
            }

            if (crossfadeActive) {
                val targetId = crossfadeTargetMediaId
                val currentId = player.currentMediaItem?.mediaId
                val onTarget = !targetId.isNullOrBlank() && targetId == currentId

                if (onTarget && !handoffActive) {
                    playbackFadeFactor.value = 0f
                    beginHandoffFromOverlap()
                    updateVolumes()
                    delay(20)
                    continue
                }

                if (!onTarget && (nextIndex == C.INDEX_UNSET || durationMs <= 0 || durationMs == C.TIME_UNSET)) {
                    stopOverlapCrossfade(resetMainFade = true)
                    delay(150)
                    continue
                }

                val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
                val tooFarFromEnd = !onTarget && remainingMs > fadeMs.toLong() + 2000L
                val nextChanged =
                    !onTarget && crossfadeTargetIndex != C.INDEX_UNSET && nextIndex != crossfadeTargetIndex
                if (tooFarFromEnd || nextChanged) {
                    stopOverlapCrossfade(resetMainFade = true)
                    delay(100)
                    continue
                }

                updateVolumes()
                delay(20)
                continue
            }

            val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
            val preloadWindowMs = fadeMs.toLong() + 1200L

            if (remainingMs in 1L..preloadWindowMs) {
                primeOverlapForNext(nextIndex)
            } else {
                unprimeOverlap()
            }

            // ── Iniciar crossfade cuando quede tiempo suficiente ──────────────
            if (overlapPrimedIndex == nextIndex && remainingMs in 1L..fadeMs.toLong()) {
                // Verificar buffer mínimo ANTES de comenzar — evita arrancar con rebuffering
                val overlap = overlapPlayer
                if (overlap != null && hasEnoughBuffer(overlap, requiredStartBufferMs(fadeMs))) {
                    beginOverlapCrossfade(fadeMs = fadeMs, remainingMs = remainingMs)
                }
                // Si no hay buffer suficiente, el próximo tick lo intentará de nuevo
                delay(50)
                continue
            }

            if (playbackFadeFactor.value != 1f) playbackFadeFactor.value = 1f
            delay(100)
        }
    }

    // ── Helpers de buffer ─────────────────────────────────────────────────────

    /**
     * Verifica si [targetPlayer] tiene al menos [minMs] de audio bufferizado,
     * o si el track es tan corto que está prácticamente completo en buffer.
     */
    private fun hasEnoughBuffer(targetPlayer: ExoPlayer, minMs: Long): Boolean {
        if (minMs <= 0L) return true
        if (targetPlayer.playbackState != Player.STATE_READY) return false

        val duration = targetPlayer.duration
        val buffered = targetPlayer.totalBufferedDuration.coerceAtLeast(0L)
        if (buffered >= minMs) return true

        // Track corto: aceptar si ya está casi completamente bufferizado
        return duration != C.TIME_UNSET &&
                targetPlayer.bufferedPosition >= duration - 150L
    }

    // ── Detección de transición gapless ───────────────────────────────────────

    /**
     * Devuelve true si [current] y [target] pertenecen al mismo álbum,
     * indicando que la transición debe ser gapless (sin crossfade).
     */
    private fun isGaplessAlbumTransition(current: MediaItem, target: MediaItem): Boolean {
        val albumA = current.metadata?.album?.id?.takeIf { it.isNotBlank() }
            ?: current.metadata?.album?.title?.takeIf { it.isNotBlank() }
            ?: current.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }

        val albumB = target.metadata?.album?.id?.takeIf { it.isNotBlank() }
            ?: target.metadata?.album?.title?.takeIf { it.isNotBlank() }
            ?: target.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }

        return albumA != null && albumA == albumB
    }

    // ── Gestión del overlap player ────────────────────────────────────────────

    private suspend fun primeOverlapForNext(nextIndex: Int) {
        val nextItem = runCatching { player.getMediaItemAt(nextIndex) }.getOrNull() ?: return
        val nextMediaId = nextItem.mediaId

        if (overlapPrimedIndex == nextIndex && overlapPrimedMediaId == nextMediaId) return

        stopOverlapCrossfade(resetMainFade = false)

        val overlap = ensureOverlapPlayer()
        overlap.clearMediaItems()
        overlap.setMediaItem(nextItem)
        overlap.prepare()
        overlap.playWhenReady = true
        overlap.volume = 0f

        overlapNormalizeFactor = fetchNormalizeFactorForMediaId(nextMediaId)
        overlapPrimedIndex = nextIndex
        overlapPrimedMediaId = nextMediaId
    }

    private fun unprimeOverlap() {
        if (crossfadeActive) return
        if (overlapPrimedIndex == C.INDEX_UNSET && overlapPrimedMediaId == null) return
        stopOverlapCrossfade(resetMainFade = false)
    }

    private fun setStreamFadesBypassed(bypassed: Boolean) {
        mainCrossfadeProcessor?.streamFadesBypassed = bypassed
        overlapCrossfadeProcessor?.streamFadesBypassed = bypassed
    }

    private fun beginOverlapCrossfade(fadeMs: Int, remainingMs: Long) {
        if (overlapPlayer == null) return

        val targetIndex = overlapPrimedIndex
        if (targetIndex != C.INDEX_UNSET && targetIndex < player.mediaItemCount) {
            onCrossfadeStart(player.getMediaItemAt(targetIndex))
        }

        setStreamFadesBypassed(true)
        crossfadeActive = true
        crossfadeStartElapsedMs = android.os.SystemClock.elapsedRealtime()
        // FIX transición discordante: duración SIEMPRE es la que pidió el usuario en el slider,
        // no el mínimo con remainingMs. Si remainingMs < fadeMs (ej: el buffer check tardó),
        // la curva DSP seguirá avanzando a velocidad real t = elapsed / fadeMs hasta alcanzar
        // t=1, alineada con el handoff post-media-item-transition que marca ExoPlayer.
        // Si remainingMs > fadeMs, el overlap simplemente queda "listo" antes y queda a la
        // espera del final real del stream emitido por onMediaItemTransition.
        crossfadeActiveDurationMs = fadeMs.coerceAtLeast(1)
        crossfadeTargetIndex = overlapPrimedIndex
        crossfadeTargetMediaId = overlapPrimedMediaId
    }

    // ── Actualización de volúmenes (por CrossfadeType) ────────────────────────

    /**
     * Calcula los gains del crossfade tipificado sin tocar player.volume,
     * ya que este lo gestiona MusicService a través de playbackFadeFactor.
     *
     * @return (outgoingGain, incomingGain) ambos en [0, 1]
     */
    private fun computeTypedGains(t: Float): Pair<Float, Float> =
        computeCrossfadeGains(t, crossfadeType.value)

    private fun updateVolumes() {
        val overlap = overlapPlayer ?: run {
            stopOverlapCrossfade(resetMainFade = true)
            return
        }

        // NOTA: ambos players (main y overlap) comparten el MISMO `sharedNormalizeFactor`.
        // La fuente de verdad es única (StateFlow del collector currentFormat en MusicService).
        // Antes usábamos `overlapNormalizeFactor` (un segundo fetch desde Room) que a menudo
        // difería del valor final del stream y provocaba picos de volumen aleatorios en el handoff.
        val baseOverlapVolume =
            (playerVolume.value * sharedNormalizeFactor.value * audioFocusVolumeFactor.value)
                .coerceIn(0f, 1f)

        // ── Fase de handoff ───────────────────────────────────────────────────
        if (handoffActive) {
            val nowElapsedMs = android.os.SystemClock.elapsedRealtime()

            val overlapDead =
                overlap.playbackState == Player.STATE_IDLE || overlap.playbackState == Player.STATE_ENDED
            val handoffElapsed = nowElapsedMs - handoffStartElapsedMs
            val handoffTimedOut = handoffElapsed >= handoffTimeoutMs

            if (overlapDead || handoffTimedOut) {
                completeHandoffFromOverlap()
                return
            }

            val overlapPositionMs = overlap.currentPosition.coerceAtLeast(0L)
            val mainPositionMs = player.currentPosition.coerceAtLeast(0L)
            val positionDriftMs = mainPositionMs - overlapPositionMs

            val shouldResyncMainToOverlap =
                !handoffSeekIssued || (
                        abs(positionDriftMs) > handoffDriftCorrectionThresholdMs &&
                                nowElapsedMs - handoffLastSyncSeekElapsedMs >= handoffReseekMinIntervalMs
                        )

            if (shouldResyncMainToOverlap) {
                handoffSeekIssued = true
                handoffTargetPositionMs = overlapPositionMs
                val currentIndex = player.currentMediaItemIndex
                if (currentIndex != C.INDEX_UNSET) {
                    player.seekTo(currentIndex, handoffTargetPositionMs)
                    handoffLastSyncSeekElapsedMs = nowElapsedMs
                }
                // NO forzamos jumps: mantenemos niveles capturados en beginHandoffFromOverlap
                return
            }

            if (!handoffRampStarted) {
                val bufferedMs = player.totalBufferedDuration.coerceAtLeast(0L)
                val mainStable =
                    player.playbackState == Player.STATE_READY &&
                            player.isPlaying &&
                            bufferedMs >= 1200L &&
                            abs(positionDriftMs) <= handoffRampStartDriftToleranceMs

                if (!mainStable) {
                    return
                }

                handoffRampStarted = true
                handoffStartElapsedMs = nowElapsedMs
            }

            val denom = handoffDurationMs.toLong().coerceAtLeast(1L)
            val elapsed = (nowElapsedMs - handoffStartElapsedMs).coerceAtLeast(0L)
            val linearT = (elapsed.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
            val smoothT = linearT * linearT * (3f - 2f * linearT)

            val mainTarget = 1f
            val mainGain = handoffMainStartFade + smoothT * (mainTarget - handoffMainStartFade)
            playbackFadeFactor.value = mainGain.coerceIn(0f, 1f)

            val overlapTarget = 0f
            val overlapGain = handoffOverlapStartFade + smoothT * (overlapTarget - handoffOverlapStartFade)
            overlap.volume = (baseOverlapVolume * overlapGain.coerceIn(0f, 1f)).coerceIn(0f, 1f)

            if (linearT >= 1f) completeHandoffFromOverlap()
            return
        }

        // ── Fase de crossfade activo (según CrossfadeType) ────────────────────
        val denom = crossfadeActiveDurationMs.toLong().coerceAtLeast(1L)
        val elapsed = (android.os.SystemClock.elapsedRealtime() - crossfadeStartElapsedMs).coerceAtLeast(0L)
        val t = (elapsed.toFloat() / denom.toFloat()).coerceIn(0f, 1f)

        // IMPORTANTE: el volumen del player principal NO lo asignamos aquí.
        // Se aplica vía playbackFadeFactor que MusicService multiplica en su
        // combine(playerVolume, normalizeFactor, audioFocusVolumeFactor, playbackFadeFactor).
        //
        // Volumen final del main:   baseMainVolume * playbackFadeFactor (= outgoingGain)
        // Volumen final del overlap: baseOverlapVolume * incomingGain (lo asignamos aquí)
        val (outgoingGain, incomingGain) = computeTypedGains(t)

        // Propagamos el gain del outgoing (main) a través de playbackFadeFactor
        playbackFadeFactor.value = outgoingGain.coerceIn(0f, 1f)

        // Aplicamos el gain del incoming (overlap) directamente sobre su volumen
        overlap.volume = (baseOverlapVolume * incomingGain).coerceIn(0f, maxSafeGainFactor)
    }

    // ── Transición de MediaItem ───────────────────────────────────────────────

    private fun handleMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!crossfadeActive) return

        val targetId = crossfadeTargetMediaId
        val newId = mediaItem?.mediaId

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            !targetId.isNullOrBlank() && targetId == newId
        ) {
            playbackFadeFactor.value = 0f
            beginHandoffFromOverlap()
            return
        }

        stopOverlapCrossfade(resetMainFade = true)
    }

    // ── Handoff: traspaso del overlap al player principal ─────────────────────

    private fun beginHandoffFromOverlap() {
        val overlap = overlapPlayer ?: run {
            stopOverlapCrossfade(resetMainFade = true)
            return
        }

        val overlapPositionMs = overlap.currentPosition.coerceAtLeast(0L)
        val now = android.os.SystemClock.elapsedRealtime()
        // Un solo StateFlow compartido = misma loudness en main y overlap.
        val baseOverlapVolume =
            (playerVolume.value * sharedNormalizeFactor.value * audioFocusVolumeFactor.value)
                .coerceIn(0f, 1f)
        val currentOverlapGain =
            if (baseOverlapVolume <= 0f) 0f
            else (overlap.volume / baseOverlapVolume).coerceIn(0f, 1f)

        handoffActive = true
        handoffSeekIssued = false
        handoffRampStarted = false
        handoffTargetPositionMs = overlapPositionMs
        handoffStartElapsedMs = now
        handoffLastSyncSeekElapsedMs = 0L
        handoffDurationMs = handoffStandardDurationMs + (crossfadeDurationMs.value * 0.15f).toInt()
            .coerceAtLeast(550)
            .coerceAtMost(950)
        handoffMainStartFade = playbackFadeFactor.value.coerceIn(0f, 1f)
        handoffOverlapStartFade = currentOverlapGain.coerceIn(0f, 1f)
        // Snap al valor compartido actual (main == overlap → snap no cambia nada, ya no hay race;
        // mantenemos la callback solo por si MusicService quería log o cleanups futuros).
        onHandoffNormalizeSnap(sharedNormalizeFactor.value.coerceIn(0.2f, maxSafeGainFactor), 300)
    }

    private fun completeHandoffFromOverlap() {
        val overlap = overlapPlayer ?: run {
            stopOverlapCrossfade(resetMainFade = true)
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        playbackFadeFactor.value = 1f
        runCatching { overlap.volume = 0f }

        handoffActive = false
        handoffStartElapsedMs = 0L
        handoffDurationMs = 0
        handoffTargetPositionMs = 0L
        handoffLastSyncSeekElapsedMs = 0L
        handoffSeekIssued = false
        handoffRampStarted = false

        crossfadeActive = false
        crossfadeTargetIndex = C.INDEX_UNSET
        crossfadeTargetMediaId = null
        crossfadeActiveDurationMs = 0
        overlapNormalizeFactor = 1f
        overlapPrimedIndex = C.INDEX_UNSET
        overlapPrimedMediaId = null
        postHandoffOverlapDrainUntil = now +
                (crossfadeDurationMs.value.toLong() * 5L / 4L).coerceAtLeast(postHandoffOverlapDrainMs + 120L) +
                300L

        streamFadesBypassController?.invoke(true, true)
        setStreamFadesBypassed(false)
    }

    // ── Stop / reset ──────────────────────────────────────────────────────────

    private fun stopOverlapCrossfade(resetMainFade: Boolean) {
        crossfadeActive = false
        crossfadeTargetIndex = C.INDEX_UNSET
        crossfadeTargetMediaId = null
        crossfadeActiveDurationMs = 0
        overlapNormalizeFactor = 1f
        overlapPrimedIndex = C.INDEX_UNSET
        overlapPrimedMediaId = null
        handoffActive = false
        handoffStartElapsedMs = 0L
        handoffDurationMs = 0
        handoffTargetPositionMs = 0L
        handoffLastSyncSeekElapsedMs = 0L
        handoffSeekIssued = false
        handoffRampStarted = false

        overlapPlayer?.let { overlap ->
            runCatching {
                overlap.volume = 0f
                overlap.stop()
                overlap.clearMediaItems()
            }
        }

        setStreamFadesBypassed(false)
        if (resetMainFade) {
            playbackFadeFactor.value = 1f
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ensureOverlapPlayer(): ExoPlayer {
        val existing = overlapPlayer
        if (existing != null) return existing
        return overlapPlayerFactory().also { overlapPlayer = it }
    }

    private suspend fun fetchNormalizeFactorForMediaId(mediaId: String): Float {
        if (!audioNormalizationEnabled.value) return 1f

        val format = withContext(Dispatchers.IO) {
            database.format(mediaId).first()
        }

        val loudness = format?.loudnessDb ?: format?.perceptualLoudnessDb ?: return 1f
        var factor = 10f.pow((-loudness.toFloat()) / 20f)
        if (factor > 1f) factor = min(factor, maxSafeGainFactor)
        return factor
    }
}