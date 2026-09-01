/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arturo254.opentune.R
import com.arturo254.opentune.constants.CrossfadeType
import com.arturo254.opentune.constants.description
import com.arturo254.opentune.constants.displayName
import me.saket.squiggles.SquigglySlider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

val LocalPreferenceInGroup = compositionLocalOf { false }

private val expressiveCardShape: Shape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 24.dp,
    bottomStart = 16.dp,
    bottomEnd = 16.dp
)

private val expressiveBadgeShape: Shape = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomStart = 8.dp,
    bottomEnd = 8.dp
)

private val expressiveBottomSheetShape: Shape = RoundedCornerShape(
    topStart = 32.dp,
    topEnd = 32.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp
)

@Composable
fun PreferenceEntry(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    description: String? = null,
    content: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    isEnabled: Boolean = true,
    elevation: Float = 0f,
) {
    val inGroup = LocalPreferenceInGroup.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            !inGroup && isPressed -> 0.97f
            !inGroup && onClick != null -> 0.99f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "prefScale"
    )

    val containerColor = if (inGroup) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val rowContent: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = if (inGroup) LocalIndication.current else null,
                    enabled = isEnabled && onClick != null,
                    onClick = onClick ?: {},
                )
                .alpha(if (isEnabled) 1f else 0.5f)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (icon != null) {
                IconContainer(
                    modifier = Modifier.align(Alignment.CenterVertically)
                ) {
                    icon()
                }
                Spacer(Modifier.width(14.dp))
            }

            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
                ProvideTextStyle(
                    MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                ) {
                    title()
                }

                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    ProvideTextStyle(
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        it()
                    }
                }

                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                content?.invoke()
            }

            if (trailingContent != null) {
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    trailingContent()
                }
            }
        }
    }

    if (inGroup) {
        rowContent()
    } else {
        Surface(
            shape = expressiveCardShape,
            color = containerColor,
            tonalElevation = elevation.dp,
            shadowElevation = if (isPressed) 4.dp else 2.dp,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (isPressed) 8.dp else 4.dp,
                    shape = expressiveCardShape,
                    clip = false
                ),
        ) {
            rowContent()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IconContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val ghostishShape = MaterialShapes.Ghostish.toShape()

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(ghostishShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(100f, 100f)
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                shape = ghostishShape
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun Badge(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = expressiveBadgeShape,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(
            1.dp,
            color.copy(alpha = 0.2f)
        ),
        modifier = modifier
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp
            ),
            color = color,
        )
    }
}

@Composable
fun <T> ListPreference(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    selectedValue: T,
    values: List<T>,
    valueText: @Composable (T) -> String,
    onValueSelected: (T) -> Unit,
    isEnabled: Boolean = true,
) {
    var showBottomSheet by remember { mutableStateOf(false) }

    if (showBottomSheet) {
        ExpressiveListBottomSheet(
            onDismiss = { showBottomSheet = false },
            values = values,
            selectedValue = selectedValue,
            valueText = valueText,
            onValueSelected = {
                showBottomSheet = false
                onValueSelected(it)
            }
        )
    }

    PreferenceEntry(
        modifier = modifier,
        title = title,
        description = valueText(selectedValue),
        icon = icon,
        onClick = { showBottomSheet = true },
        isEnabled = isEnabled,
    )
}

@Composable
inline fun <reified T : Enum<T>> EnumListPreference(
    modifier: Modifier = Modifier,
    noinline title: @Composable () -> Unit,
    noinline icon: (@Composable () -> Unit)?,
    selectedValue: T,
    noinline valueText: @Composable (T) -> String,
    noinline onValueSelected: (T) -> Unit,
    isEnabled: Boolean = true,
) {
    ListPreference(
        modifier = modifier,
        title = title,
        icon = icon,
        selectedValue = selectedValue,
        values = enumValues<T>().toList(),
        valueText = valueText,
        onValueSelected = onValueSelected,
        isEnabled = isEnabled,
    )
}

@Composable
fun SwitchPreference(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    description: String? = null,
    icon: (@Composable () -> Unit)? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isEnabled: Boolean = true,
) {
    val rotation by animateFloatAsState(
        targetValue = if (checked) 0f else 180f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "switchRotation"
    )

    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        animationSpec = tween(300),
        label = "trackColor"
    )

    PreferenceEntry(
        modifier = modifier,
        title = title,
        description = description,
        icon = icon,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = isEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = trackColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedTrackColor = trackColor,
                    checkedBorderColor = Color.Transparent,
                    uncheckedBorderColor = Color.Transparent,
                ),
                thumbContent = {
                    Icon(
                        painter = painterResource(
                            id = if (checked) R.drawable.check else R.drawable.close
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .size(SwitchDefaults.IconSize)
                            .graphicsLayer {
                                rotationZ = rotation
                            },
                        tint = if (checked) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }
            )
        },
        onClick = { onCheckedChange(!checked) },
        isEnabled = isEnabled
    )
}

@Composable
fun EditTextPreference(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    isInputValid: (String) -> Boolean = { it.isNotEmpty() },
    isEnabled: Boolean = true,
) {
    var showBottomSheet by remember { mutableStateOf(false) }

    if (showBottomSheet) {
        ExpressiveTextFieldBottomSheet(
            initialTextFieldValue = TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            ),
            singleLine = singleLine,
            isInputValid = isInputValid,
            onDone = onValueChange,
            onDismiss = { showBottomSheet = false },
        )
    }

    PreferenceEntry(
        modifier = modifier,
        title = title,
        description = value,
        icon = icon,
        onClick = { showBottomSheet = true },
        isEnabled = isEnabled,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderPreference(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    value: Float,
    onValueChange: (Float) -> Unit,
    isEnabled: Boolean = true,
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(value) }

    if (showBottomSheet) {
        ExpressiveActionBottomSheet(
            titleBar = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.history_duration),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            onDismiss = {
                sliderValue = value
                showBottomSheet = false
            },
            onConfirm = {
                showBottomSheet = false
                onValueChange.invoke(sliderValue)
            },
            onCancel = {
                sliderValue = value
                showBottomSheet = false
            },
            onReset = {
                sliderValue = 30f
            },
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.seconds,
                            sliderValue.roundToInt(),
                            sliderValue.roundToInt()
                        ),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        ),
                    )

                    Spacer(Modifier.height(16.dp))

                    SquigglySlider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 15f..60f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }

    PreferenceEntry(
        modifier = modifier,
        title = title,
        description = value.roundToInt().toString(),
        icon = icon,
        onClick = { showBottomSheet = true },
        isEnabled = isEnabled,
    )
}

// ── Helper de curvas de crossfade (duplicado UI-safe respecto a CrossfadeAudio) ──
private object CrossfadeCurvePreviewUtils {
    fun computeGains(t: Float, type: CrossfadeType): Pair<Float, Float> {
        val clampedT = t.coerceIn(0f, 1f)
        return when (type) {
            CrossfadeType.EQUAL_POWER -> {
                val theta = clampedT * (PI / 2.0)
                cos(theta).toFloat() to sin(theta).toFloat()
            }
            CrossfadeType.LINEAR -> (1f - clampedT) to clampedT
            CrossfadeType.LOGARITHMIC -> {
                val eps = 0.001f
                val out = (ln((1f - clampedT) * (1f - eps) + eps) / ln(eps)).coerceIn(0f, 1f)
                val inc = (ln(clampedT * (1f - eps) + eps) / ln(eps)).coerceIn(0f, 1f)
                (1f - out) to inc
            }
            CrossfadeType.EXPONENTIAL -> {
                val k = 3f
                val expIn = (1f - Math.exp(-k * clampedT.toDouble()).toFloat() /
                    (1f - Math.exp(-k.toDouble()).toFloat())).coerceIn(0f, 1f)
                val expOut = (1f - Math.exp(-k * (1 - clampedT).toDouble()).toFloat() /
                    (1f - Math.exp(-k.toDouble()).toFloat())).coerceIn(0f, 1f)
                (1f - expOut) to expIn
            }
            CrossfadeType.SMOOTHSTEP -> {
                val s = clampedT * clampedT * (3f - 2f * clampedT)
                (1f - s) to s
            }
            CrossfadeType.SMOOTHERSTEP -> {
                val s = clampedT * clampedT * clampedT * (
                    clampedT * (clampedT * 6f - 15f) + 10f
                )
                (1f - s) to s
            }
            CrossfadeType.CONSTANT_GAIN -> {
                sqrt(1f - clampedT) to sqrt(clampedT)
            }
            CrossfadeType.FAST_START -> {
                val inc = 1f - (1f - clampedT).pow(3)
                val out = (1f - clampedT).pow(1.5f)
                out to inc
            }
            CrossfadeType.SLOW_START -> {
                val inc = clampedT.pow(3)
                val out = 1f - (1f - clampedT).pow(0.35f)
                (1f - out) to inc
            }
            CrossfadeType.WAVE_MIX -> {
                val theta = clampedT * PI
                val out = ((1.0 + cos(theta)) * 0.5).toFloat()
                val inc = ((1.0 - cos(theta)) * 0.5).toFloat()
                out to inc
            }
            CrossfadeType.TRIANGLE -> {
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
}

@Composable
private fun CrossfadeCurvePreview(
    type: CrossfadeType,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    widthDp: Int = 140,
    heightDp: Int = 70,
) {
    val outgoingColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val incomingColor = if (isSelected) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val sumHighlight = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        Color.Unspecified
    }

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .size(widthDp.dp, heightDp.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                }
            )
            .padding(6.dp)
    ) {
        val w = size.width
        val h = size.height
        val samples = 40
        val stepX = w / samples.toFloat()

        // ── Grid suave ────────────────────────────────────────────────────
        // Línea horizontal central (y = 0.5)
        drawLine(
            color = gridColor,
            start = Offset(0f, h * 0.5f),
            end = Offset(w, h * 0.5f),
            strokeWidth = 0.8.dp.toPx(),
        )
        // Linea vertical central (t = 0.5)
        drawLine(
            color = gridColor,
            start = Offset(w * 0.5f, 0f),
            end = Offset(w * 0.5f, h),
            strokeWidth = 0.8.dp.toPx(),
        )

        // ── Área de suma (solo cuando está seleccionado) ─────────────────
        if (sumHighlight != Color.Unspecified) {
            val sumPath = Path().apply {
                moveTo(0f, h)
                for (i in 0..samples) {
                    val t = i / samples.toFloat()
                    val (outG, inG) = CrossfadeCurvePreviewUtils.computeGains(t, type)
                    val sum = (outG + inG).coerceAtMost(1.414f)
                    val x = i * stepX
                    val y = h - (sum / 1.414f) * h
                    lineTo(x, y)
                }
                lineTo(w, h)
                close()
            }
            drawPath(path = sumPath, color = sumHighlight)
        }

        // ── Curvas ───────────────────────────────────────────────────────
        fun buildPath(isOutgoing: Boolean): Path = Path().apply {
            val first = true
            for (i in 0..samples) {
                val t = i / samples.toFloat()
                val (outG, inG) = CrossfadeCurvePreviewUtils.computeGains(t, type)
                val gain = if (isOutgoing) outG else inG
                val x = i * stepX
                val y = h - gain * h
                if (first && i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        drawPath(
            path = buildPath(isOutgoing = true),
            color = outgoingColor,
            style = Stroke(width = if (isSelected) 2.4.dp.toPx() else 1.8.dp.toPx())
        )
        drawPath(
            path = buildPath(isOutgoing = false),
            color = incomingColor,
            style = Stroke(width = if (isSelected) 2.4.dp.toPx() else 1.8.dp.toPx())
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossfadeSliderPreference(
    modifier: Modifier = Modifier,
    value: Int,
    onValueChange: (Int) -> Unit,
    crossfadeType: CrossfadeType,
    onCrossfadeTypeChange: (CrossfadeType) -> Unit,
    isEnabled: Boolean = true,
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var localValue by remember { mutableFloatStateOf(value.toFloat()) }
    var localType by remember { mutableStateOf(crossfadeType) }

    androidx.compose.runtime.LaunchedEffect(value) {
        localValue = value.toFloat()
    }
    androidx.compose.runtime.LaunchedEffect(crossfadeType) {
        localType = crossfadeType
    }

    val displayValue = localValue.roundToInt().coerceIn(0, 10)
    val isCrossfadeEnabled = displayValue > 0

    val descriptionText = when (displayValue) {
        0 -> stringResource(R.string.crossfade_disabled_description)
        else -> buildString {
            append(localType.displayName())
            append(" • ")
            append(
                androidx.compose.ui.res.pluralStringResource(
                    id = R.plurals.seconds,
                    count = displayValue,
                    displayValue
                )
            )
        }
    }

    val availableTypes = remember { CrossfadeType.values().toList() }

    if (showBottomSheet) {
        ExpressiveActionBottomSheet(
            titleBar = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.audio_crossfade_title),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = localType.description(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            },
            onDismiss = {
                localValue = value.toFloat()
                localType = crossfadeType
                showBottomSheet = false
            },
            onConfirm = {
                val finalValue = localValue.roundToInt().coerceIn(0, 10)
                onValueChange(finalValue)
                onCrossfadeTypeChange(localType)
                showBottomSheet = false
            },
            onCancel = {
                localValue = value.toFloat()
                localType = crossfadeType
                showBottomSheet = false
            },
            onReset = {
                localValue = 0f
                localType = CrossfadeType.DEFAULT
            },
            content = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // ── Valor numérico grande ─────────────────────────────────
                    val valueColor by animateColorAsState(
                        targetValue = if (isCrossfadeEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(300),
                        label = "valueColor"
                    )

                    Text(
                        text = if (displayValue == 0) {
                            stringResource(R.string.dark_theme_off)
                        } else {
                            pluralStringResource(R.plurals.seconds, displayValue, displayValue)
                        },
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = valueColor,
                    )

                    Spacer(Modifier.height(16.dp))

                    SquigglySlider(
                        value = localValue,
                        onValueChange = {
                            localValue = it.roundToInt()
                                .coerceIn(0, 10)
                                .toFloat()
                        },
                        valueRange = 0f..10f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(0, 2, 4, 6, 8, 10).forEach { mark ->
                            val isActive = displayValue >= mark && mark > 0
                            Badge(
                                text = "${mark}s",
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                                modifier = Modifier
                                    .alpha(if (isActive) 1f else 0.5f)
                            )
                        }
                    }

                    // ── Selector de tipos con previews ────────────────────────
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = stringResource(R.string.crossfade_transition_style),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.crossfade_transition_style_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 10.dp)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(
                            start = 2.dp,
                            end = 2.dp,
                            bottom = 2.dp
                        )
                    ) {
                        itemsIndexed(
                            items = availableTypes,
                            key = { _, type -> type.name }
                        ) { idx, type ->
                            val isSelected = localType == type

                            val animatedScale by animateFloatAsState(
                                targetValue = if (isSelected) 1f else 0.97f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "cardScale_$idx"
                            )
                            val animatedBorder by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                },
                                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                                label = "borderColor_$idx"
                            )
                            val animatedBg by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                label = "bgColor_$idx"
                            )

                            Surface(
                                onClick = { localType = type },
                                modifier = Modifier
                                    .width(160.dp)
                                    .graphicsLayer {
                                        scaleX = animatedScale
                                        scaleY = animatedScale
                                    },
                                shape = RoundedCornerShape(
                                    topStart = 22.dp,
                                    topEnd = 12.dp,
                                    bottomStart = 12.dp,
                                    bottomEnd = 22.dp
                                ),
                                color = animatedBg,
                                border = BorderStroke(
                                    width = if (isSelected) 1.8.dp else 1.dp,
                                    color = animatedBorder
                                ),
                                tonalElevation = if (isSelected) 4.dp else 0.dp,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CrossfadeCurvePreview(
                                        type = type,
                                        isSelected = isSelected,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = type.displayName(),
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = if (isSelected) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Medium
                                            },
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = type.description().take(48).let {
                                            if (it.length < type.description().length) "$it…" else it
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            },
                                            lineHeight = 12.sp
                                        ),
                                        maxLines = 2,
                                        minLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // ── Detalle del tipo seleccionado ─────────────────────────
                    Spacer(Modifier.height(18.dp))
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 10.dp,
                            bottomStart = 10.dp,
                            bottomEnd = 18.dp
                        ),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CrossfadeCurvePreview(
                                type = localType,
                                isSelected = true,
                                widthDp = 120,
                                heightDp = 64,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = localType.displayName(),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = localType.description(),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 14.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    PreferenceEntry(
        modifier = modifier,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.audio_crossfade_title))
                if (isCrossfadeEnabled) {
                    Badge(
                        text = "${displayValue}s · ${localType.displayName()}",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        subtitle = {
            if (isCrossfadeEnabled) {
                CrossfadeCurvePreview(
                    type = localType,
                    isSelected = true,
                    widthDp = 200,
                    heightDp = 42,
                )
            } else null
        },
        description = descriptionText,
        icon = {
            IconContainer {
                Icon(
                    painterResource(R.drawable.graphic_eq),
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        onClick = { if (isEnabled) showBottomSheet = true },
        isEnabled = isEnabled,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberPickerPreference(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    value: Int,
    onValueChange: (Int) -> Unit,
    minValue: Int = 0,
    maxValue: Int = 10,
    valueText: (Int) -> String = { it.toString() },
    isEnabled: Boolean = true,
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(value.toFloat()) }

    if (showBottomSheet) {
        ExpressiveActionBottomSheet(
            titleBar = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    title()
                }
            },
            onDismiss = {
                sliderValue = value.toFloat()
                showBottomSheet = false
            },
            onConfirm = {
                val rounded = sliderValue.roundToInt().coerceIn(minValue, maxValue)
                sliderValue = rounded.toFloat()
                showBottomSheet = false
                onValueChange.invoke(rounded)
            },
            onCancel = {
                sliderValue = value.toFloat()
                showBottomSheet = false
            },
            onReset = {
                sliderValue = minValue.toFloat()
            },
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val rounded = sliderValue.roundToInt().coerceIn(minValue, maxValue)
                    Text(
                        text = valueText(rounded),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        ),
                    )

                    Spacer(Modifier.height(16.dp))

                    SquigglySlider(
                        value = sliderValue,
                        onValueChange = {
                            sliderValue = it.roundToInt()
                                .coerceIn(minValue, maxValue)
                                .toFloat()
                        },
                        valueRange = minValue.toFloat()..maxValue.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val step = (maxValue - minValue) / 4
                        (0..4).map { minValue + (it * step) }.forEach { mark ->
                            if (mark in minValue..maxValue) {
                                Text(
                                    text = mark.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp
                                    ),
                                    color = if (rounded >= mark) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    PreferenceEntry(
        modifier = modifier,
        title = title,
        description = valueText(value),
        icon = icon,
        onClick = { if (isEnabled) showBottomSheet = true },
        isEnabled = isEnabled,
    )
}

@Composable
fun PreferenceGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }

        Surface(
            shape = expressiveCardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = expressiveCardShape,
                    clip = false
                ),
        ) {
            CompositionLocalProvider(LocalPreferenceInGroup provides true) {
                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun PreferenceGroupDivider(modifier: Modifier = Modifier) {
    Column {
        HorizontalDivider(
            modifier = modifier.padding(start = 60.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .padding(start = 60.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
fun PreferenceGroupTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

// ── BOTTOM SHEETS EXPRESIVOS ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveActionBottomSheet(
    titleBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = expressiveBottomSheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(36.dp, 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )
        },
        modifier = Modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            titleBar()

            Spacer(Modifier.height(24.dp))

            content()

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (onReset != null) {
                    Arrangement.SpaceBetween
                } else {
                    Arrangement.End
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onReset != null) {
                    TextButton(
                        onClick = onReset,
                    ) {
                        Text(
                            stringResource(R.string.reset),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onCancel,
                    ) {
                        Text(
                            stringResource(R.string.cancel),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = onConfirm,
                    ) {
                        Text(
                            stringResource(R.string.save),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ExpressiveListBottomSheet(
    onDismiss: () -> Unit,
    values: List<T>,
    selectedValue: T,
    valueText: @Composable (T) -> String,
    onValueSelected: (T) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = expressiveBottomSheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(36.dp, 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )
        },
        modifier = Modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.select_option),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            ExpressiveList(
                items = values,
                selectedValue = selectedValue,
                valueText = valueText,
                onItemSelected = onValueSelected
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                ) {
                    Text(
                        stringResource(R.string.close),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveTextFieldBottomSheet(
    initialTextFieldValue: TextFieldValue,
    singleLine: Boolean,
    isInputValid: (String) -> Boolean,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var textFieldValue by remember { mutableStateOf(initialTextFieldValue) }
    val isValid = isInputValid(textFieldValue.text)

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = expressiveBottomSheetShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(36.dp, 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )
        },
        modifier = Modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.enter_value),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            TextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                singleLine = singleLine,
                shape = expressiveCardShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
                isError = !isValid && textFieldValue.text.isNotEmpty()
            )

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                ) {
                    Text(
                        stringResource(R.string.cancel),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = {
                        if (isValid) {
                            onDone(textFieldValue.text)
                            onDismiss()
                        }
                    },
                    enabled = isValid,
                ) {
                    Text(
                        stringResource(R.string.save),
                        fontWeight = FontWeight.Medium,
                        color = if (isValid) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ExpressiveList(
    items: List<T>,
    selectedValue: T?,
    valueText: @Composable (T) -> String,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            horizontal = 4.dp,
            vertical = 8.dp
        )
    ) {
        items(
            items = items,
            key = { it.hashCode() }
        ) { item ->

            val isSelected = item == selectedValue

            val animatedContainerColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "containerColor"
            )

            val animatedContentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "contentColor"
            )

            val animatedScale by animateFloatAsState(
                targetValue = if (isSelected) 1.015f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "scale"
            )

            val animatedCornerRadius by animateDpAsState(
                targetValue = if (isSelected) 28.dp else 20.dp,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "cornerRadius"
            )

            Surface(
                onClick = {
                    onItemSelected(item)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                    },
                shape = RoundedCornerShape(
                    topStart = animatedCornerRadius,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = animatedCornerRadius
                ),
                color = animatedContainerColor,
                contentColor = animatedContentColor,
                tonalElevation = if (isSelected) 3.dp else 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 18.dp,
                            vertical = 14.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    // Indicador expresivo de selección
                    AnimatedContent(
                        targetState = isSelected,
                        transitionSpec = {
                            scaleIn(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ) + fadeIn() togetherWith
                                    scaleOut() + fadeOut()
                        },
                        label = "selectionIndicator"
                    ) { selected ->

                        if (selected) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = 8.dp,
                                    bottomEnd = 16.dp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            R.drawable.check
                                        ),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        } else {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                MaterialTheme.colorScheme
                                                    .onSurfaceVariant
                                                    .copy(alpha = 0.45f)
                                            )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(
                        modifier = Modifier.width(16.dp)
                    )

                    Text(
                        text = valueText(item),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isSelected) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                    )

                    AnimatedVisibility(
                        visible = isSelected,
                        enter = fadeIn() + expandHorizontally(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}