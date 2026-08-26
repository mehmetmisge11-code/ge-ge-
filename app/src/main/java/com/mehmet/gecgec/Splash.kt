package com.mehmet.gecgec

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Acilis perdesi.
 *
 * Yesil perde ortada yarim daire dislerle kenetli duruyor. Yarim saniye sonra
 * ust yari yukari, alt yari asagi kayiyor; ortadaki logo da tam ortasindan
 * ikiye boluniyor. Boslugu uygulamanin kendisi dolduruyor.
 */

private val GreenSoft = Color(0xFFA9DD79)
private val Green = Color(0xFF7CC33F)
private val GreenDeep = Color(0xFF3C7F26)
private val MarkInk = Color(0xFF2F6B1C)

/** Kac dis. Tek sayida olursa iki yarim tam oturmaz - cift birak. */
private const val TEETH = 6

private const val HOLD_MS = 550L
private const val OPEN_MS = 950

/**
 * Kenetli kenar. Disler sirayla asagi ve yukari kabariyor;
 * ust yarinin cikintisi alt yarinin oyugu oluyor, bu yuzden tam otururlar.
 */
private class ToothShape(private val top: Boolean) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val step = w / TEETH
        val r = step / 2f
        val p = Path()

        if (top) {
            p.moveTo(0f, 0f)
            p.lineTo(w, 0f)
            p.lineTo(w, mid)
            // Kenari sagdan sola dolas
            for (i in TEETH - 1 downTo 0) {
                val x = i * step
                p.arcTo(
                    Rect(x, mid - r, x + step, mid + r),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = if (i % 2 == 0) 180f else -180f,
                    forceMoveTo = false
                )
            }
        } else {
            p.moveTo(0f, mid)
            // Ayni kenari soldan saga dolas
            for (i in 0 until TEETH) {
                val x = i * step
                p.arcTo(
                    Rect(x, mid - r, x + step, mid + r),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = if (i % 2 == 0) -180f else 180f,
                    forceMoveTo = false
                )
            }
            p.lineTo(w, h)
            p.lineTo(0f, h)
        }

        p.close()
        return Outline.Generic(p)
    }
}

@Composable
fun SplashGate(content: @Composable () -> Unit) {
    var popped by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        popped = true
        delay(HOLD_MS)
        open = true
        delay(OPEN_MS.toLong() + 150L)
        gone = true
    }

    val ease = remember { CubicBezierEasing(0.72f, 0f, 0.16f, 1f) }

    val shift by animateFloatAsState(
        targetValue = if (open) 1.02f else 0f,
        animationSpec = tween(OPEN_MS, easing = ease),
        label = "perde"
    )
    val reveal by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(760, delayMillis = 120),
        label = "uygulama"
    )
    val markScale by animateFloatAsState(
        targetValue = if (popped) 1f else 0.7f,
        animationSpec = tween(520, easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.25f)),
        label = "logo"
    )
    val markAlpha by animateFloatAsState(
        targetValue = if (popped) 1f else 0f,
        animationSpec = tween(380),
        label = "logoSaydam"
    )

    Box(Modifier.fillMaxSize()) {

        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = if (gone) 1f else reveal
                val s = 0.965f + 0.035f * reveal
                scaleX = s
                scaleY = s
            }
        ) { content() }

        if (!gone) {
            Curtain(true, -shift, markScale, markAlpha)
            Curtain(false, shift, markScale, markAlpha)
        }
    }
}

@Composable
private fun Curtain(top: Boolean, shift: Float, markScale: Float, markAlpha: Float) {
    val shape = remember(top) { ToothShape(top) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = shift * size.height }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (top) listOf(GreenSoft, Green) else listOf(Green, GreenDeep)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        MarkGlyph(
            Modifier.size(122.dp).graphicsLayer {
                scaleX = markScale
                scaleY = markScale
                alpha = markAlpha
            }
        )
    }
}

/** Beyaz yuvarlak kare icinde yesil konum nisani. */
@Composable
private fun MarkGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val sw = s * 0.065f

        drawRoundRect(
            color = Color.White.copy(alpha = 0.95f),
            topLeft = Offset(s * 0.05f, s * 0.05f),
            size = Size(s * 0.90f, s * 0.90f),
            cornerRadius = CornerRadius(s * 0.27f)
        )
        drawCircle(MarkInk, radius = s * 0.25f, center = c, style = Stroke(width = sw))
        drawCircle(MarkInk, radius = s * 0.095f, center = c)

        drawLine(MarkInk, Offset(c.x, s * 0.12f), Offset(c.x, s * 0.24f), sw, StrokeCap.Round)
        drawLine(MarkInk, Offset(c.x, s * 0.76f), Offset(c.x, s * 0.88f), sw, StrokeCap.Round)
        drawLine(MarkInk, Offset(s * 0.12f, c.y), Offset(s * 0.24f, c.y), sw, StrokeCap.Round)
        drawLine(MarkInk, Offset(s * 0.76f, c.y), Offset(s * 0.88f, c.y), sw, StrokeCap.Round)
    }
}
