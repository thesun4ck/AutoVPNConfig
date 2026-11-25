package com.example.vpnui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

fun appBackground(theme: AppThemeMode): Color = when (theme) {
    AppThemeMode.LIGHT -> Color(0xFFF3F7FD)
    AppThemeMode.DARK -> Color(0xFF0C1728)
    AppThemeMode.GLASS -> Color(0xFF071333)
}

/** Light and dark are flat colors. Liquid glass shows the gradient (or the user's photo / GIF) behind translucent cards. */
@Composable
fun AppBackdrop(
    theme: AppThemeMode,
    uri: String?,
    isGif: Boolean,
    blurDp: Float,
    dim: Float,
    onLoadFailed: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(appBackground(theme))) {
        if (theme == AppThemeMode.GLASS) {
            if (uri != null) {
                val parsed = remember(uri) { Uri.parse(uri) }
                val animated = isGif && Build.VERSION.SDK_INT >= 28
                val m = Modifier.fillMaxSize().then(if (blurDp > 0f && !animated) Modifier.blur(blurDp.dp) else Modifier)
                if (isGif && Build.VERSION.SDK_INT >= 28) {
                    GifBackground(parsed, m, onLoadFailed)
                } else {
                    PhotoBackground(parsed, m, onLoadFailed)
                }
            } else {
                DefaultGlassWallpaper()
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF050816).copy(alpha = dim.coerceIn(0f, 0.9f))))
        }
    }
}

@Composable
private fun DefaultGlassWallpaper() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF071333), Color(0xFF0E225C), Color(0xFF241862), Color(0xFF3E176D)),
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.6f, size.height)
            )
        )
        fun orb(color: Color, cx: Float, cy: Float, r: Float, a: Float) {
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r,
                center = Offset(cx, cy)
            )
        }
        orb(Color(0xFF3B82F6), size.width * 0.05f, size.height * 0.05f, size.width * 0.75f, 0.65f)
        orb(Color(0xFF8B5CF6), size.width * 0.95f, size.height * 0.80f, size.width * 0.80f, 0.65f)
        orb(Color(0xFF06B6D4), size.width * 0.50f, size.height * 0.45f, size.width * 0.55f, 0.38f)
    }
}

@Composable
private fun PhotoBackground(uri: Uri, modifier: Modifier, onFail: () -> Unit) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        val loaded = loadBitmap(context, uri)
        if (loaded == null) onFail() else bmp = loaded
    }
    bmp?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop) }
}

@RequiresApi(28)
@Composable
private fun GifBackground(uri: Uri, modifier: Modifier, onFail: () -> Unit) {
    val context = LocalContext.current
    var drawable by remember(uri) { mutableStateOf<Drawable?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(uri) {
        val d = withContext(Dispatchers.IO) { runCatching { decodeAnimated(context, uri) }.getOrNull() }
        if (d == null) onFail() else drawable = d
    }

    val d = drawable ?: return
    DisposableEffect(d) {
        val anim = d as? AnimatedImageDrawable
        // Drawable keeps only a weak reference to its callback, so hold it strongly here.
        val cb = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { tick++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {}
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }
        d.callback = cb
        anim?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        anim?.start()
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> anim?.start()
                Lifecycle.Event.ON_PAUSE -> anim?.stop() // no drawing and no battery use in background
                else -> {}
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            anim?.stop()
            if (d.callback === cb) d.callback = null
        }
    }

    Canvas(modifier) {
        val frame = tick // read to redraw on every animation frame
        val iw = d.intrinsicWidth.coerceAtLeast(1)
        val ih = d.intrinsicHeight.coerceAtLeast(1)
        val k = max(size.width / iw, size.height / ih)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate((size.width - iw * k) / 2f, (size.height - ih * k) / 2f)
            nc.scale(k, k)
            d.setBounds(0, 0, iw, ih)
            d.draw(nc)
            nc.restore()
        }
        if (frame < 0) return@Canvas
    }
}

@RequiresApi(28)
private fun decodeAnimated(context: Context, uri: Uri, maxSide: Int = 720): Drawable {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeDrawable(src) { dec, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val m = max(w, h)
        if (m > maxSide) {
            val k = maxSide.toFloat() / m
            dec.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
        }
    }
}

suspend fun loadBitmap(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                // ImageDecoder applies EXIF rotation itself
                ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val m = max(w, h)
                    if (m > maxSide) {
                        val k = maxSide.toFloat() / m
                        dec.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
                    }
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                legacyBitmap(context, uri, maxSide)
            }
        }.getOrNull()
    }

private fun legacyBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val cr = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val orientation = runCatching {
        cr.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
}
