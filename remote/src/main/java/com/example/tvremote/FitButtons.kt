package com.example.tvremote

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/*
 * Buttons whose label shrinks to fit.
 *
 * THE RULE FOR THE WHOLE APP: a button is never written as Button { Text(...) }. Use one of the Fit buttons below, or
 * FitText inside a button that has more than a label in it. Then no label can ever run past its button.
 *
 * What stays fixed: the button's width, height, shape and padding. They come from the Modifier the caller gives and
 * from Material's own defaults, and these wrappers never change them. Only the label's font size moves.
 *
 * How the label behaves:
 *  - It is always one line: no wrapping, ever.
 *  - If it is too wide, the font size goes down one step at a time (1 sp by default) until it fits.
 *  - It never goes below a floor (10 sp by default). If it still does not fit there, it is cut with "...".
 *  - The line height of the text style is kept, so shrinking does not change how tall the label is.
 */

/** The defaults every Fit control shares. Change them here to change the whole app. */
object FitTextDefaults {
    /** The smallest size the label may shrink to. */
    val MinFontSize: TextUnit = 10.sp

    /** How much the size drops on each step. */
    val Step: TextUnit = 1.sp

    /** The starting size when neither the caller nor the text style gives one. */
    val FallbackFontSize: TextUnit = 14.sp
}

/**
 * One line of text that shrinks, one [step] at a time, until it fits the width it is given.
 *
 * The starting size is [maxFontSize], or the size in [style] when that is not given. It never goes below [minFontSize]
 * (and never starts below it either, so a style that is already smaller is left alone). At the floor, text that still
 * does not fit ends in "...". Sizes are in sp.
 *
 * The text stays hidden for the few milliseconds it takes to find the size, so there is no visible jump.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxFontSize: TextUnit = TextUnit.Unspecified,
    minFontSize: TextUnit = FitTextDefaults.MinFontSize,
    step: TextUnit = FitTextDefaults.Step
) {
    val startSp = when {
        maxFontSize.isSpecified -> maxFontSize.value
        style.fontSize.isSpecified -> style.fontSize.value
        else -> FitTextDefaults.FallbackFontSize.value
    }
    val floorSp = minOf(minFontSize.value, startSp)
    val stepSp = step.value.coerceAtLeast(0.1f)
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        // The width the label may use. When it changes, or anything about the text does, the search starts again from
        // the full size, so a label also grows back when more room appears.
        val availableWidth = constraints.maxWidth
        var sizeSp by remember(text, style, startSp, floorSp, stepSp, availableWidth, density) { mutableFloatStateOf(startSp) }
        var settled by remember(text, style, startSp, floorSp, stepSp, availableWidth, density) { mutableStateOf(false) }

        Text(
            text = text,
            color = color,
            style = style,
            fontSize = sizeSp.sp,
            maxLines = 1,
            softWrap = false,
            overflow = if (sizeSp <= floorSp) TextOverflow.Ellipsis else TextOverflow.Clip,
            onTextLayout = { result ->
                if (result.didOverflowWidth && sizeSp > floorSp) {
                    sizeSp = (sizeSp - stepSp).coerceAtLeast(floorSp)
                } else {
                    settled = true
                }
            },
            modifier = Modifier.drawWithContent { if (settled) drawContent() }
        )
    }
}

/** A filled button with a label that shrinks to fit. [textStyle] is the button's own label style when null. */
@Composable
fun FitButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    textStyle: TextStyle? = null,
    minFontSize: TextUnit = FitTextDefaults.MinFontSize
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding
    ) {
        FitText(label, style = textStyle ?: LocalTextStyle.current, minFontSize = minFontSize)
    }
}

/** A tonal button with a label that shrinks to fit. */
@Composable
fun FitTonalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    textStyle: TextStyle? = null,
    minFontSize: TextUnit = FitTextDefaults.MinFontSize
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding
    ) {
        FitText(label, style = textStyle ?: LocalTextStyle.current, minFontSize = minFontSize)
    }
}

/** An outlined button with a label that shrinks to fit. */
@Composable
fun FitOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    textStyle: TextStyle? = null,
    minFontSize: TextUnit = FitTextDefaults.MinFontSize
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding
    ) {
        FitText(label, style = textStyle ?: LocalTextStyle.current, minFontSize = minFontSize)
    }
}

/** A text button with a label that shrinks to fit. */
@Composable
fun FitTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    textStyle: TextStyle? = null,
    minFontSize: TextUnit = FitTextDefaults.MinFontSize
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        contentPadding = contentPadding
    ) {
        FitText(label, style = textStyle ?: LocalTextStyle.current, minFontSize = minFontSize)
    }
}
