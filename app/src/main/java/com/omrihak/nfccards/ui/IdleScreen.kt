package com.omrihak.nfccards.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * "Tap a card here": ripples around a bobbing card with a smiley, on a friendly landscape.
 * No text, no controls. [busy] speeds up the ripples while a card's content is being fetched;
 * a change in [problems] gives the card a short wordless wobble.
 */
@Composable
fun IdleScreen(busy: Boolean, problems: Int) {
    val transition = rememberInfiniteTransition(label = "idle")
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (busy) 1000 else 2600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val wobble = remember { Animatable(0f) }
    LaunchedEffect(problems) {
        if (problems > 0) {
            repeat(3) {
                wobble.animateTo(1f, tween(70))
                wobble.animateTo(-1f, tween(70))
            }
            wobble.animateTo(0f, tween(70))
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val unit = min(size.width, size.height)
        val center = Offset(size.width / 2f, size.height * 0.48f)

        drawRect(Brush.verticalGradient(listOf(Color(0xFF8FD3F4), Color(0xFFE6F7D4))))
        drawCircle(Color(0xFF7BC96F), radius = size.width * 0.7f, center = Offset(size.width * 0.1f, size.height * 1.08f))
        drawCircle(Color(0xFF5DB85A), radius = size.width * 0.6f, center = Offset(size.width * 0.95f, size.height * 1.12f))

        val p0 = pulse.value
        for (i in 0 until 3) {
            val p = (p0 + i / 3f) % 1f
            drawCircle(
                color = Color.White.copy(alpha = (1f - p) * 0.8f),
                radius = unit * (0.18f + 0.32f * p),
                center = center,
                style = Stroke(width = unit * 0.025f),
            )
        }

        val bob = sin(p0 * 2f * PI.toFloat()) * unit * 0.015f
        val cardW = unit * 0.26f
        val cardH = cardW * 1.25f
        val topLeft = Offset(center.x - cardW / 2f + wobble.value * unit * 0.03f, center.y - cardH / 2f + bob)
        drawRoundRect(Color.White, topLeft, Size(cardW, cardH), CornerRadius(cardW * 0.14f))

        val face = Offset(topLeft.x + cardW / 2f, topLeft.y + cardH / 2f)
        val r = cardW * 0.3f
        drawCircle(Color(0xFFFFC93C), r, face)
        drawCircle(Color(0xFF4A3B2A), r * 0.1f, Offset(face.x - r * 0.35f, face.y - r * 0.2f))
        drawCircle(Color(0xFF4A3B2A), r * 0.1f, Offset(face.x + r * 0.35f, face.y - r * 0.2f))
        drawArc(
            color = Color(0xFF4A3B2A),
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(face.x - r * 0.5f, face.y - r * 0.35f),
            size = Size(r, r * 0.8f),
            style = Stroke(width = r * 0.1f, cap = StrokeCap.Round),
        )
    }
}
