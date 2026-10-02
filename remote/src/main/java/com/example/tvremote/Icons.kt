package com.example.tvremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Remote icons, drawn natively in the shapes of Material Symbols (home, settings, apps, keyboard, ...). */
enum class Ico { Back, Home, Settings, Apps, Keyboard, Up, Down, Left, Right, Forward, Rewind }

@Composable
fun RIcon(icon: Ico, tint: Color, dp: Dp = 28.dp) {
    Canvas(Modifier.size(dp)) {
        scale(size.minDimension / 24f, Offset.Zero) {
            val stroke = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun path(closed: Boolean, vararg p: Float): Path = Path().apply {
                moveTo(p[0], p[1])
                for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
                if (closed) close()
            }
            fun line(vararg p: Float) = drawPath(path(false, *p), tint, style = stroke)
            fun poly(vararg p: Float) = drawPath(path(true, *p), tint)
            when (icon) {
                Ico.Back -> { line(20f, 12f, 4f, 12f); line(10f, 6f, 4f, 12f, 10f, 18f) }
                Ico.Up -> line(6f, 15f, 12f, 9f, 18f, 15f)
                Ico.Down -> line(6f, 9f, 12f, 15f, 18f, 9f)
                Ico.Left -> line(15f, 6f, 9f, 12f, 15f, 18f)
                Ico.Right -> line(9f, 6f, 15f, 12f, 9f, 18f)
                Ico.Home -> poly(12f, 3f, 2f, 12f, 5f, 12f, 5f, 20f, 10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f, 19f, 20f, 19f, 12f, 22f, 12f)
                Ico.Forward -> { poly(4f, 6f, 12.5f, 12f, 4f, 18f); poly(13f, 6f, 21.5f, 12f, 13f, 18f) }
                Ico.Rewind -> { poly(11f, 6f, 2.5f, 12f, 11f, 18f); poly(20f, 6f, 11.5f, 12f, 20f, 18f) }
                Ico.Apps -> for (r in 0..2) for (c in 0..2)
                    drawRect(tint, topLeft = Offset(4f + c * 6f, 4f + r * 6f), size = Size(4f, 4f))
                Ico.Keyboard -> {
                    drawRoundRect(tint, topLeft = Offset(2f, 5f), size = Size(20f, 14f), cornerRadius = CornerRadius(2f), style = Stroke(width = 1.8f))
                    for (r in 0..1) for (c in 0..4)
                        drawRect(tint, topLeft = Offset(5f + c * 3f, 8f + r * 3f), size = Size(2f, 2f))
                    drawRect(tint, topLeft = Offset(7f, 15f), size = Size(10f, 1.8f))
                }
                Ico.Settings -> {
                    drawCircle(tint, radius = 5f, center = Offset(12f, 12f), style = Stroke(width = 2.6f))
                    for (k in 0 until 8) rotate(k * 45f, Offset(12f, 12f)) {
                        drawRect(tint, topLeft = Offset(10.8f, 1.8f), size = Size(2.4f, 4.2f))
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteButton(icon: Ico, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 68.dp), contentPadding = PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RIcon(icon, LocalContentColor.current)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
fun DKey(icon: Ico, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(72.dp)) {
        RIcon(icon, LocalContentColor.current, 36.dp)
    }
}
