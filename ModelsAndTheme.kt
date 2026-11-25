package com.nanotune.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor

// ---------------------------------------------------------------------------
// Core Data Models
// ---------------------------------------------------------------------------
@Immutable
data class TrackItem(
    val id: Long,
    val uri: Uri,
    val path: String,
    val fileName: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val year: String,
    val trackNo: Int,
    val displayNum: Int,
    val durationMs: Long,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val bitrate: Int,
    val channels: Int,
    val fileSize: Long,
    val codecDetail: String,
    val formatLabel: String,
    val albumId: Long,
    val albumKey: String,
    val hasLyrics: Boolean,
    val lyricsSource: String,
    val folderTreeUri: String = "",
    val coverHash: String = "",
    val dateModified: Long = 0L
)

@Immutable
data class AlbumGroup(
    val albumKey: String,
    val albumName: String,
    val artistName: String,
    val year: String,
    val trackCount: Int,
    val sampleTrack: TrackItem,
    val coverHashes: List<String> = emptyList()
)

@Immutable
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

@Immutable
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord>
)

@Immutable
data class ParsedLyrics(
    val isSynced: Boolean,
    val hasElrcWords: Boolean,
    val lines: List<LyricLine>,
    val rawText: String,
    val sourceLabel: String
)

@Immutable
data class AppSettings(
    val musicFolders: List<String> = emptyList(),
    val recursiveScan: Boolean = true,
    val adaptiveColors: Boolean = true,
    val alwaysBlurBg: Boolean = true,
    val blurRadiusDp: Float = 15f,
    val liquidGlassMode: Boolean = true,
    val liquidGlassIntensity: Float = 0.70f,
    val fontPresetIndex: Int = 0,
    val lyricFontSizeSp: Float = 20f,
    val hasCompletedOnboarding: Boolean = false
)

@Immutable
data class ThemeColors(
    val primary: Color,
    val secondary: Color
)

@Immutable
data class FontPreset(
    val name: String,
    val headerFamily: FontFamily,
    val uiFamily: FontFamily
)

private fun nativeAndroidFont(
    familyName: String,
    style: Int = Typeface.NORMAL,
    fallback: FontFamily = FontFamily.SansSerif
): FontFamily {
    return runCatching {
        FontFamily(Typeface.create(familyName, style))
    }.getOrDefault(fallback)
}

val NanoFontPresets = listOf(
    FontPreset(
        name = "Default Sans (Roboto)",
        headerFamily = FontFamily.SansSerif,
        uiFamily = FontFamily.SansSerif
    ),
    FontPreset(
        name = "Editorial Serif (Noto Serif)",
        headerFamily = FontFamily.Serif,
        uiFamily = FontFamily.SansSerif
    ),
    FontPreset(
        name = "Geometric Clean (Sans-Serif Medium)",
        headerFamily = nativeAndroidFont("sans-serif-medium", Typeface.NORMAL),
        uiFamily = nativeAndroidFont("sans-serif-medium", Typeface.NORMAL)
    ),
    FontPreset(
        name = "Rounded Humanist (Sans-Serif Light)",
        headerFamily = nativeAndroidFont("sans-serif-light", Typeface.NORMAL),
        uiFamily = nativeAndroidFont("sans-serif-light", Typeface.NORMAL)
    ),
    FontPreset(
        name = "Condensed Bold (Sans-Serif Condensed)",
        headerFamily = nativeAndroidFont("sans-serif-condensed", Typeface.BOLD),
        uiFamily = nativeAndroidFont("sans-serif-condensed", Typeface.NORMAL)
    ),
    FontPreset(
        name = "Classic Book Serif",
        headerFamily = FontFamily.Serif,
        uiFamily = FontFamily.Serif
    ),
    FontPreset(
        name = "Monospace Tech (Droid Sans Mono)",
        headerFamily = FontFamily.Monospace,
        uiFamily = FontFamily.Monospace
    ),
    FontPreset(
        name = "Casual Script (Cursive)",
        headerFamily = FontFamily.Cursive,
        uiFamily = FontFamily.SansSerif
    ),
    FontPreset(
        name = "Heavy Display (Sans-Serif Black)",
        headerFamily = nativeAndroidFont("sans-serif-black", Typeface.NORMAL),
        uiFamily = nativeAndroidFont("sans-serif-black", Typeface.NORMAL)
    ),
    FontPreset(
        name = "Thin Minimal (Sans-Serif Thin)",
        headerFamily = nativeAndroidFont("sans-serif-thin", Typeface.NORMAL),
        uiFamily = nativeAndroidFont("sans-serif-light", Typeface.NORMAL)
    ),
    FontPreset(
        name = "Editorial Mono Hybrid",
        headerFamily = FontFamily.Serif,
        uiFamily = FontFamily.Monospace
    ),
    FontPreset(
        name = "Studio Condensed",
        headerFamily = nativeAndroidFont("sans-serif-condensed", Typeface.NORMAL),
        uiFamily = nativeAndroidFont("sans-serif-condensed", Typeface.NORMAL)
    )
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("nanotune_prefs", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readFromPrefs())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun readFromPrefs(): AppSettings {
        val rawFolders = prefs.getString("music_folders", "") ?: ""
        val folders = rawFolders.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        return AppSettings(
            musicFolders = folders,
            recursiveScan = prefs.getBoolean("recursive_scan", true),
            adaptiveColors = prefs.getBoolean("adaptive_colors", true),
            alwaysBlurBg = prefs.getBoolean("always_blur_bg", true),
            blurRadiusDp = prefs.getFloat("blur_radius", 15f).coerceIn(10f, 66f),
            liquidGlassMode = prefs.getBoolean("liquid_glass_mode", true),
            liquidGlassIntensity = prefs.getFloat("liquid_glass_intensity", 0.70f).coerceIn(0.15f, 1.0f),
            fontPresetIndex = prefs.getInt("font_preset_index", 0).coerceIn(0, NanoFontPresets.lastIndex),
            lyricFontSizeSp = prefs.getFloat("lyric_font_size", 20f).coerceIn(14f, 28f),
            hasCompletedOnboarding = prefs.getBoolean("onboarding_done", false)
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value).let { s ->
            s.copy(
                blurRadiusDp = s.blurRadiusDp.coerceIn(10f, 66f),
                liquidGlassIntensity = s.liquidGlassIntensity.coerceIn(0.15f, 1.0f),
                fontPresetIndex = s.fontPresetIndex.coerceIn(0, NanoFontPresets.lastIndex),
                lyricFontSizeSp = s.lyricFontSizeSp.coerceIn(14f, 28f)
            )
        }
        _settings.value = next
        prefs.edit()
            .putString("music_folders", next.musicFolders.joinToString("|"))
            .putBoolean("recursive_scan", next.recursiveScan)
            .putBoolean("adaptive_colors", next.adaptiveColors)
            .putBoolean("always_blur_bg", next.alwaysBlurBg)
            .putFloat("blur_radius", next.blurRadiusDp)
            .putBoolean("liquid_glass_mode", next.liquidGlassMode)
            .putFloat("liquid_glass_intensity", next.liquidGlassIntensity)
            .putInt("font_preset_index", next.fontPresetIndex)
            .putFloat("lyric_font_size", next.lyricFontSizeSp)
            .putBoolean("onboarding_done", next.hasCompletedOnboarding)
            .apply()
    }
}

// ---------------------------------------------------------------------------
// Dynamic HSV Color Extractor & Disk Cover Cache
// ---------------------------------------------------------------------------
object ColorExtractor {

    val DefaultTheme = ThemeColors(
        primary = Color(0xFFFFD83D),
        secondary = Color(0xFFFFE885)
    )

    val MonochromeSilverTheme = ThemeColors(
        primary = Color(0xFFE4E8EC),
        secondary = Color(0xFFB4BCC4)
    )

    private val themeCache = LruCache<String, ThemeColors>(256)
    private val thumbCache = LruCache<String, Bitmap>(256)
    private val largeCoverCache = LruCache<String, Bitmap>(64)

    fun coversDir(context: Context): File {
        return File(context.cacheDir, "covers").apply { if (!exists()) mkdirs() }
    }

    fun clearCoverCaches(context: Context) {
        thumbCache.evictAll()
        largeCoverCache.evictAll()
        themeCache.evictAll()
        runCatching {
            coversDir(context).listFiles()?.forEach { it.delete() }
        }
    }

       fun computeVisualCoverHash(bitmap: Bitmap): String {
        val sample = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val pixels = IntArray(72)
        sample.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        if (sample !== bitmap) sample.recycle()

        val luma = IntArray(72)
        var sum = 0L
        for (i in 0 until 72) {
            val px = pixels[i]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val y = (r * 30 + g * 59 + b * 11) / 100
            luma[i] = y
            sum += y
        }
        val avg = (sum / 72L).toInt()
        var bits = 0L
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val idx = row * 9 + col
                bits = bits shl 1
                if (luma[idx] > luma[idx + 1] || luma[idx] > avg + 12) {
                    bits = bits or 1L
                }
            }
        }
        return "%016x".format(bits)
    }


    fun getCachedCover(key: String): Bitmap? {
        if (key.isEmpty()) return null
        return thumbCache.get(key) ?: largeCoverCache.get(key)
    }

    fun getCachedLargeCover(key: String): Bitmap? {
        if (key.isEmpty()) return null
        return largeCoverCache.get(key) ?: thumbCache.get(key)
    }

    suspend fun loadCachedBitmapByHash(
        context: Context,
        coverHash: String,
        large: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (coverHash.isEmpty()) return@withContext null
        val mem = if (large) largeCoverCache.get(coverHash) else thumbCache.get(coverHash)
        if (mem != null) return@withContext mem

        val dir = coversDir(context)
        val file = File(dir, if (large) "${coverHash}_lg.jpg" else "${coverHash}_sm.jpg")
        val fallbackFile = if (!file.exists()) {
            File(dir, if (large) "${coverHash}_sm.jpg" else "${coverHash}_lg.jpg")
        } else file

        if (fallbackFile.exists()) {
            val dec = runCatching {
                BitmapFactory.decodeFile(fallbackFile.absolutePath)
            }.getOrNull()
            if (dec != null) {
                if (large && fallbackFile.name.endsWith("_lg.jpg")) {
                    largeCoverCache.put(coverHash, dec)
                } else {
                    thumbCache.put(coverHash, dec)
                }
                return@withContext dec
            }
        }
        null
    }

    fun storeCoverToDiskAndMemory(context: Context, rawBitmap: Bitmap): String {
        val hash = computeVisualCoverHash(rawBitmap)
        val dir = coversDir(context)
        val smFile = File(dir, "${hash}_sm.jpg")
        val lgFile = File(dir, "${hash}_lg.jpg")

        val lgBmp = if (lgFile.exists()) {
            largeCoverCache.get(hash) ?: BitmapFactory.decodeFile(lgFile.absolutePath)
        } else {
            val scaled = scaleCenterCrop(rawBitmap, 360)
            runCatching {
                FileOutputStream(lgFile).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
            scaled
        }

        val smBmp = if (smFile.exists()) {
            thumbCache.get(hash) ?: BitmapFactory.decodeFile(smFile.absolutePath)
        } else {
            val scaled = scaleCenterCrop(rawBitmap, 160)
            runCatching {
                FileOutputStream(smFile).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
                }
            }
            scaled
        }

        if (lgBmp != null) largeCoverCache.put(hash, lgBmp)
        if (smBmp != null) thumbCache.put(hash, smBmp)
        return hash
    }

    private fun scaleCenterCrop(src: Bitmap, targetSize: Int): Bitmap {
        val w = src.width.coerceAtLeast(1)
        val h = src.height.coerceAtLeast(1)
        val minEdge = min(w, h)
        val xOff = (w - minEdge) / 2
        val yOff = (h - minEdge) / 2
        val cropped = if (w != h) Bitmap.createBitmap(src, xOff, yOff, minEdge, minEdge) else src
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }

    fun extractThemeColors(bitmap: Bitmap): ThemeColors {
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
            val width = small.width
            val height = small.height
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            if (small !== bitmap) small.recycle()

            val buckets = Array(24) { mutableListOf<FloatArray>() }
            var chromaticCount = 0
            val hsv = FloatArray(3)

            for (px in pixels) {
                val r = AndroidColor.red(px)
                val g = AndroidColor.green(px)
                val b = AndroidColor.blue(px)
                AndroidColor.RGBToHSV(r, g, b, hsv)
                val s = hsv[1]
                val v = hsv[2]
                if (s > 0.22f && v > 0.16f) {
                    chromaticCount++
                    if (v < 0.96f) {
                        val bin = ((hsv[0] / 360f) * 24f).toInt().coerceIn(0, 23)
                        buckets[bin].add(floatArrayOf(hsv[0], s, v))
                    }
                }
            }

            if (chromaticCount < pixels.size * 0.08f) {
                return MonochromeSilverTheme
            }

            var topBucket: MutableList<FloatArray>? = null
            for (b in buckets) {
                if (b.isNotEmpty() && (topBucket == null || b.size > topBucket.size)) {
                    topBucket = b
                }
            }
            if (topBucket == null || topBucket.isEmpty()) {
                return MonochromeSilverTheme
            }

            var sumSin = 0.0
            var sumCos = 0.0
            var sumS = 0f
            var sumV = 0f
            for (item in topBucket) {
                val rad = Math.toRadians(item[0].toDouble())
                sumSin += sin(rad)
                sumCos += cos(rad)
                sumS += item[1]
                sumV += item[2]
            }
            val count = topBucket.size.toFloat()
            val avgH = (((Math.toDegrees(atan2(sumSin, sumCos)) + 360.0) % 360.0)).toFloat()
            val avgS = (sumS / count).coerceIn(0.50f, 0.88f)
            val avgV = (sumV / count).coerceIn(0.74f, 0.94f)

            val primaryArgb = AndroidColor.HSVToColor(floatArrayOf(avgH, avgS, avgV))
            val secH = (avgH + 28.8f) % 360f
            val secS = max(0.45f, avgS * 0.80f)
            val secV = min(0.96f, avgV * 1.05f)
            val secondaryArgb = AndroidColor.HSVToColor(floatArrayOf(secH, secS, secV))

            ThemeColors(
                primary = Color(primaryArgb),
                secondary = Color(secondaryArgb)
            )
        } catch (_: Throwable) {
            DefaultTheme
        }
    }

    fun luminance(color: Color): Float {
        val argb = color.toArgb()
        val r = AndroidColor.red(argb)
        val g = AndroidColor.green(argb)
        val b = AndroidColor.blue(argb)
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }

    suspend fun loadTrackArtwork(
        context: Context,
        track: TrackItem,
        preferLarge: Boolean = true
    ): Pair<Bitmap?, ThemeColors> = withContext(Dispatchers.IO) {
        val trackKey = track.coverHash.ifEmpty { track.uri.toString() }

        if (track.coverHash.isNotEmpty()) {
            val cached = loadCachedBitmapByHash(context, track.coverHash, large = preferLarge)
            if (cached != null) {
                val colors = themeCache.get(track.coverHash) ?: extractThemeColors(cached).also {
                    themeCache.put(track.coverHash, it)
                }
                return@withContext cached to colors
            }
        }

        val memByUri = if (preferLarge) largeCoverCache.get(trackKey) else thumbCache.get(trackKey)
        if (memByUri != null) {
            val colors = themeCache.get(trackKey) ?: extractThemeColors(memByUri).also {
                themeCache.put(trackKey, it)
            }
            return@withContext memByUri to colors
        }

        var bmp: Bitmap? = null

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, track.uri)
            val artBytes = mmr.embeddedPicture
            if (artBytes != null && artBytes.isNotEmpty()) {
                bmp = decodeSampledBitmap(artBytes, 512, 512)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { mmr.release() }
        }

        if (bmp == null && track.path.isNotEmpty()) {
            val parentDir = File(track.path).parentFile
            if (parentDir != null && parentDir.exists()) {
                for (name in listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg")) {
                    val imgFile = File(parentDir, name)
                    if (imgFile.exists() && imgFile.isFile) {
                        bmp = runCatching { decodeSampledBitmap(imgFile.readBytes(), 512, 512) }.getOrNull()
                        if (bmp != null) break
                    }
                }
            }
        }

        if (bmp == null && track.albumId > 0L) {
            try {
                val artUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    track.albumId
                )
                context.contentResolver.openInputStream(artUri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.isNotEmpty()) {
                        bmp = decodeSampledBitmap(bytes, 512, 512)
                    }
                }
            } catch (_: Throwable) {
            }
        }

                val finalBmp = bmp
        if (finalBmp != null) {
            val hash = storeCoverToDiskAndMemory(context, finalBmp)
            val colors = themeCache.get(hash) ?: extractThemeColors(finalBmp).also {
                themeCache.put(hash, it)
            }
            val resultBmp = loadCachedBitmapByHash(context, hash, large = preferLarge) ?: finalBmp
            resultBmp to colors
        } else {
            null to DefaultTheme
        }

    }

    fun compressWatchArtwork(bitmap: Bitmap): ByteArray {
        val scaled = scaleCenterCrop(bitmap, 300)
        var quality = 88
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            quality -= 10
        } while (bytes.size > 148_000 && quality >= 38)
        if (scaled !== bitmap) scaled.recycle()
        return bytes
    }

    private fun decodeSampledBitmap(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var inSample = 1
        while (opts.outWidth / inSample > reqW * 2 || opts.outHeight / inSample > reqH * 2) {
            inSample *= 2
        }
        val decOpts = BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decOpts)
    }

    fun buildFallbackCoverBitmap(accent: Color = DefaultTheme.primary, size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)
        val argb = accent.toArgb()
        val acR = AndroidColor.red(argb)
        val acG = AndroidColor.green(argb)
        val acB = AndroidColor.blue(argb)
        val linePaint = Paint()
        for (y in 0 until size) {
            val t = y.toFloat() / size.toFloat()
            val r = (12 + acR * 0.16f * (1f - t)).toInt().coerceIn(0, 255)
            val g = (14 + acG * 0.16f * (1f - t * 0.5f)).toInt().coerceIn(0, 255)
            val b = (16 + acB * 0.16f * (1f - t * 0.3f)).toInt().coerceIn(0, 255)
            linePaint.color = AndroidColor.rgb(r, g, b)
            canvas.drawLine(0f, y.toFloat(), size.toFloat(), y.toFloat(), linePaint)
        }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.014f
            color = argb
        }
        canvas.drawCircle(size * 0.5f, size * 0.5f, size * 0.27f, ringPaint)
        return bmp
    }

    fun createProcessedBackdrop(source: Bitmap, blurRadiusDp: Float): Bitmap {
        val targetW = 360
        val targetH = ((source.height.toFloat() / max(1, source.width)) * targetW)
            .toInt()
            .coerceIn(200, 640)
        val scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true)

        val adjusted = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(adjusted)
        val satMatrix = ColorMatrix().apply { setSaturation(1.25f) }
        val brightMatrix = ColorMatrix(
            floatArrayOf(
                0.58f, 0f, 0f, 0f, 0f,
                0f, 0.58f, 0f, 0f, 0f,
                0f, 0f, 0.58f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        satMatrix.postConcat(brightMatrix)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(satMatrix)
        }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== source) scaled.recycle()

        val radius = (blurRadiusDp * 0.55f).toInt().coerceIn(2, 38)
        val blurred = fastStackBlur(adjusted, radius)
        if (blurred !== adjusted) adjusted.recycle()
        return blurred
    }

    private fun fastStackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in dv.indices) dv[idx] = idx / divsum

        yi = 0
        yw = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0; gsum = 0; rsum = 0
            boutsum = 0; goutsum = 0; routsum = 0
            binsum = 0; ginsum = 0; rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius
            x = 0
            while (x < w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) vmin[x] = min(x + radius + 1, wm)
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            bsum = 0; gsum = 0; rsum = 0
            boutsum = 0; goutsum = 0; routsum = 0
            binsum = 0; ginsum = 0; rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                if (i < hm) yp += w
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) vmin[y] = min(y + r1, hm) * w
                p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}

// ---------------------------------------------------------------------------
// Theme State, Zero-Bleed Liquid Glass / Opaque Surface & Blurred Backdrop
// ---------------------------------------------------------------------------
@Immutable
data class NanoThemeState(
    val accent: Color = ColorExtractor.DefaultTheme.primary,
    val accentSecondary: Color = ColorExtractor.DefaultTheme.secondary,
    val liquidGlassMode: Boolean = true,
    val liquidGlassIntensity: Float = 0.70f,
    val fontPreset: FontPreset = NanoFontPresets[0],
    val backdropImage: ImageBitmap? = null,
    val rootSize: IntSize = IntSize.Zero
)

val LocalNanoTheme = compositionLocalOf { NanoThemeState() }

@Composable
fun NanoTuneTheme(
    rawThemeColors: ThemeColors,
    settings: AppSettings,
    backdropBitmap: Bitmap?,
    rootSize: IntSize,
    content: @Composable () -> Unit
) {
    val targetPrimary = if (settings.adaptiveColors) rawThemeColors.primary else ColorExtractor.DefaultTheme.primary
    val targetSecondary = if (settings.adaptiveColors) rawThemeColors.secondary else ColorExtractor.DefaultTheme.secondary

    val animatedPrimary by animateColorAsState(
        targetValue = targetPrimary,
        animationSpec = tween(durationMillis = 450),
        label = "accentPrimary"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = targetSecondary,
        animationSpec = tween(durationMillis = 450),
        label = "accentSecondary"
    )

    val preset = NanoFontPresets[settings.fontPresetIndex.coerceIn(0, NanoFontPresets.lastIndex)]
    val imageBmp = remember(backdropBitmap) { backdropBitmap?.asImageBitmap() }

    val state = NanoThemeState(
        accent = animatedPrimary,
        accentSecondary = animatedSecondary,
        liquidGlassMode = settings.liquidGlassMode,
        liquidGlassIntensity = settings.liquidGlassIntensity,
        fontPreset = preset,
        backdropImage = imageBmp,
        rootSize = rootSize
    )

    val colorScheme = darkColorScheme(
        primary = animatedPrimary,
        secondary = animatedSecondary,
        background = Color(0xFF0B0D0E),
        surface = Color(0xFF11151A),
        onPrimary = if (ColorExtractor.luminance(animatedPrimary) > 0.45f) Color(0xFF07100B) else Color.White,
        onBackground = Color(0xFFEAF0EE),
        onSurface = Color(0xFFEAF0EE)
    )

    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = preset.headerFamily, fontWeight = FontWeight.Black, fontSize = 28.sp),
        headlineMedium = TextStyle(fontFamily = preset.headerFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = preset.uiFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp),
        bodyMedium = TextStyle(fontFamily = preset.uiFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp),
        labelSmall = TextStyle(fontFamily = preset.uiFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    )

    CompositionLocalProvider(LocalNanoTheme provides state) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

@Composable
fun BlurredAlbumBackdrop(
    processedBackdrop: Bitmap?,
    showBlurred: Boolean,
    accent: Color,
    blurRadiusDp: Float,
    modifier: Modifier = Modifier
) {
    val imgBitmap = remember(processedBackdrop) { processedBackdrop?.asImageBitmap() }

    Box(modifier = modifier.fillMaxSize()) {
        if (showBlurred && imgBitmap != null) {
            val hwBlurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.graphicsLayer {
                    val px = (blurRadiusDp * 0.45f * density).coerceAtLeast(1f)
                    renderEffect = RenderEffect
                        .createBlurEffect(px, px, Shader.TileMode.MIRROR)
                        .asComposeRenderEffect()
                }
            } else {
                Modifier
            }
            Image(
                bitmap = imgBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(hwBlurModifier)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0x62080B0C),
                                Color(
                                    red = accent.red * 0.09f,
                                    green = accent.green * 0.09f,
                                    blue = accent.blue * 0.09f,
                                    alpha = 0.34f
                                ),
                                Color(0x8C06090A)
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF0B0D0E),
                                Color(0xFF0F1416),
                                Color(0xFF090E10)
                            )
                        )
                    )
            )
        }
    }
}

fun Modifier.liquidGlass(
    cornerRadius: Dp = 16.dp,
    accentGlow: Boolean = false,
    darkAlpha: Float = 0.92f
): Modifier = composed {
    val theme = LocalNanoTheme.current
    val shape = RoundedCornerShape(cornerRadius)
    var boundsInRoot by remember { mutableStateOf<Rect?>(null) }

    if (!theme.liquidGlassMode) {
        val borderBrush = if (accentGlow) {
            Brush.linearGradient(
                listOf(
                    theme.accent.copy(alpha = 0.58f),
                    Color.White.copy(alpha = 0.14f)
                )
            )
        } else {
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.12f),
                    Color.White.copy(alpha = 0.12f)
                )
            )
        }
        this
            .clip(shape)
            .background(Color(0xFF11151A).copy(alpha = 0.98f), shape)
            .drawWithContent {
                drawContent()
                val rPx = cornerRadius.toPx()
                val strokePx = 1.dp.toPx()
                val half = strokePx * 0.5f
                if (size.width > strokePx && size.height > strokePx) {
                    drawRoundRect(
                        brush = borderBrush,
                        topLeft = Offset(half, half),
                        size = Size(size.width - strokePx, size.height - strokePx),
                        cornerRadius = CornerRadius(rPx, rPx),
                        style = Stroke(width = strokePx)
                    )
                }
            }
    } else {
        val intensity = theme.liquidGlassIntensity
        val backdrop = theme.backdropImage
        val rootSz = theme.rootSize

        this
            .shadow(
                elevation = (10f * intensity).dp,
                shape = shape,
                ambientColor = theme.accent.copy(alpha = 0.28f),
                spotColor = Color.Black.copy(alpha = 0.72f)
            )
            .onGloballyPositioned { coords ->
                boundsInRoot = coords.boundsInRoot()
            }
            .clip(shape)
            .drawBehind {
                val rPx = cornerRadius.toPx()
                val clipShape = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(rPx, rPx)))
                }

                drawRoundRect(
                    color = Color(0xFF0E1217),
                    cornerRadius = CornerRadius(rPx, rPx)
                )

                if (
                    backdrop != null &&
                    backdrop.width > 0 &&
                    backdrop.height > 0 &&
                    boundsInRoot != null &&
                    rootSz.width > 0 &&
                    rootSz.height > 0 &&
                    size.width >= 1f &&
                    size.height >= 1f
                ) {
                    val b = boundsInRoot!!
                    val scale = max(
                        rootSz.width.toFloat() / backdrop.width.toFloat(),
                        rootSz.height.toFloat() / backdrop.height.toFloat()
                    )
                    val drawnW = backdrop.width * scale
                    val drawnH = backdrop.height * scale
                    val offX = (rootSz.width - drawnW) * 0.5f
                    val offY = (rootSz.height - drawnH) * 0.5f

                    val srcX = ((b.left - offX) / scale).roundToInt().coerceIn(0, backdrop.width - 1)
                    val srcY = ((b.top - offY) / scale).roundToInt().coerceIn(0, backdrop.height - 1)
                    val srcW = (b.width / scale).roundToInt().coerceIn(1, backdrop.width - srcX)
                    val srcH = (b.height / scale).roundToInt().coerceIn(1, backdrop.height - srcY)

                    clipPath(clipShape) {
                        drawImage(
                            image = backdrop,
                            srcOffset = IntOffset(srcX, srcY),
                            srcSize = IntSize(srcW, srcH),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(
                                size.width.roundToInt().coerceAtLeast(1),
                                size.height.roundToInt().coerceAtLeast(1)
                            ),
                            alpha = (0.35f + 0.65f * intensity).coerceIn(0.35f, 1.0f)
                        )
                    }
                }

                val frostAlpha = max(darkAlpha, 0.92f) - (0.16f * intensity)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0E1217).copy(alpha = frostAlpha.coerceIn(0.76f, 0.94f)),
                            theme.accent.copy(alpha = if (accentGlow) 0.14f * intensity else 0.05f * intensity),
                            Color(0xFF0B0E12).copy(alpha = (frostAlpha + 0.04f).coerceIn(0.80f, 0.96f))
                        )
                    ),
                    cornerRadius = CornerRadius(rPx, rPx)
                )
            }
            .drawWithContent {
                drawContent()
                val rPx = cornerRadius.toPx()
                val rimStroke = 1.4.dp.toPx()
                val half = rimStroke * 0.5f
                if (size.width > rimStroke && size.height > rimStroke) {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.52f * intensity),
                                theme.accent.copy(alpha = 0.36f * intensity),
                                Color.White.copy(alpha = 0.05f),
                                Color.White.copy(alpha = 0.16f * intensity)
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        ),
                        topLeft = Offset(half, half),
                        size = Size(size.width - rimStroke, size.height - rimStroke),
                        cornerRadius = CornerRadius(rPx, rPx),
                        style = Stroke(width = rimStroke)
                    )
                }
            }
    }
}

@Composable
fun NanoGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    accentGlow: Boolean = false,
    darkAlpha: Float = 0.92f,
    content: @Composable BoxScope.() -> Unit
) {
    val theme = LocalNanoTheme.current
    val shape = RoundedCornerShape(cornerRadius)

    if (!theme.liquidGlassMode) {
        Box(
            modifier = modifier.liquidGlass(
                cornerRadius = cornerRadius,
                accentGlow = accentGlow,
                darkAlpha = 1.0f
            ),
            content = content
        )
    } else {
        var boundsInRoot by remember { mutableStateOf<Rect?>(null) }
        val intensity = theme.liquidGlassIntensity
        val backdrop = theme.backdropImage
        val rootSz = theme.rootSize

        Box(
            modifier = modifier
                .shadow(
                    elevation = (10f * intensity).dp,
                    shape = shape,
                    ambientColor = theme.accent.copy(alpha = 0.28f),
                    spotColor = Color.Black.copy(alpha = 0.72f)
                )
                .onGloballyPositioned { coords ->
                    boundsInRoot = coords.boundsInRoot()
                }
                .clip(shape)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xFF0E1217))
                    .let { baseMod ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            baseMod.graphicsLayer {
                                val px = (18f * intensity * density).coerceAtLeast(1f)
                                renderEffect = RenderEffect
                                    .createBlurEffect(px, px, Shader.TileMode.CLAMP)
                                    .asComposeRenderEffect()
                            }
                        } else {
                            baseMod
                        }
                    }
                    .drawBehind {
                        if (
                            backdrop != null &&
                            backdrop.width > 0 &&
                            backdrop.height > 0 &&
                            boundsInRoot != null &&
                            rootSz.width > 0 &&
                            rootSz.height > 0 &&
                            size.width >= 1f &&
                            size.height >= 1f
                        ) {
                            val b = boundsInRoot!!
                            val scale = max(
                                rootSz.width.toFloat() / backdrop.width.toFloat(),
                                rootSz.height.toFloat() / backdrop.height.toFloat()
                            )
                            val drawnW = backdrop.width * scale
                            val drawnH = backdrop.height * scale
                            val offX = (rootSz.width - drawnW) * 0.5f
                            val offY = (rootSz.height - drawnH) * 0.5f

                            val srcX = ((b.left - offX) / scale).roundToInt().coerceIn(0, backdrop.width - 1)
                            val srcY = ((b.top - offY) / scale).roundToInt().coerceIn(0, backdrop.height - 1)
                            val srcW = (b.width / scale).roundToInt().coerceIn(1, backdrop.width - srcX)
                            val srcH = (b.height / scale).roundToInt().coerceIn(1, backdrop.height - srcY)

                            drawImage(
                                image = backdrop,
                                srcOffset = IntOffset(srcX, srcY),
                                srcSize = IntSize(srcW, srcH),
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(
                                    size.width.roundToInt().coerceAtLeast(1),
                                    size.height.roundToInt().coerceAtLeast(1)
                                ),
                                alpha = (0.40f + 0.60f * intensity).coerceIn(0.40f, 1.0f)
                            )
                        }
                    }
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val rPx = cornerRadius.toPx()
                        val frostAlpha = (max(darkAlpha, 0.92f) - 0.16f * intensity).coerceIn(0.76f, 0.94f)
                        drawRoundRect(
                            color = Color(0xFF0E1217).copy(alpha = frostAlpha),
                            cornerRadius = CornerRadius(rPx, rPx)
                        )
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.08f * intensity),
                                    theme.accent.copy(alpha = if (accentGlow) 0.12f * intensity else 0.04f * intensity),
                                    Color.Transparent
                                )
                            ),
                            cornerRadius = CornerRadius(rPx, rPx)
                        )
                    }
                    .drawWithContent {
                        drawContent()
                        val rPx = cornerRadius.toPx()
                        val rimStroke = 1.4.dp.toPx()
                        val half = rimStroke * 0.5f
                        if (size.width > rimStroke && size.height > rimStroke) {
                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.54f * intensity),
                                        theme.accent.copy(alpha = 0.34f * intensity),
                                        Color.White.copy(alpha = 0.06f),
                                        Color.White.copy(alpha = 0.16f * intensity)
                                    ),
                                    start = Offset.Zero,
                                    end = Offset(size.width, size.height)
                                ),
                                topLeft = Offset(half, half),
                                size = Size(size.width - rimStroke, size.height - rimStroke),
                                cornerRadius = CornerRadius(rPx, rPx),
                                style = Stroke(width = rimStroke)
                            )
                        }
                    }
            )

            content()
        }
    }
}

@Composable
fun MarioStarIcon(
    tint: Color = Color(0xFFFFD83D),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val s = minOf(size.width, size.height)
        val cx = size.width * 0.5f
        val cy = size.height * 0.52f
        val outerR = s * 0.43f
        val innerR = s * 0.24f

        val pts = Array(10) { i ->
            val angle = -PI / 2.0 + i * (PI / 5.0)
            val rad = if (i % 2 == 0) outerR else innerR
            Offset(
                x = cx + (rad * cos(angle)).toFloat(),
                y = cy + (rad * sin(angle)).toFloat()
            )
        }

        val path = Path()
        val n = pts.size
        for (i in 0 until n) {
            val prev = pts[(i - 1 + n) % n]
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            val pIn = Offset(cur.x * 0.72f + prev.x * 0.28f, cur.y * 0.72f + prev.y * 0.28f)
            val pOut = Offset(cur.x * 0.72f + next.x * 0.28f, cur.y * 0.72f + next.y * 0.28f)
            if (i == 0) path.moveTo(pIn.x, pIn.y) else path.lineTo(pIn.x, pIn.y)
            path.quadraticTo(cur.x, cur.y, pOut.x, pOut.y)
        }
        path.close()

        val lighter = Color(
            red = (tint.red + (1f - tint.red) * 0.25f).coerceIn(0f, 1f),
            green = (tint.green + (1f - tint.green) * 0.25f).coerceIn(0f, 1f),
            blue = (tint.blue + (1f - tint.blue) * 0.25f).coerceIn(0f, 1f)
        )
        val darker = Color(
            red = (tint.red * 0.80f).coerceIn(0f, 1f),
            green = (tint.green * 0.80f).coerceIn(0f, 1f),
            blue = (tint.blue * 0.80f).coerceIn(0f, 1f)
        )
        val outline = Color(
            red = (tint.red * 0.40f).coerceIn(0f, 1f),
            green = (tint.green * 0.34f).coerceIn(0f, 1f),
            blue = (tint.blue * 0.22f).coerceIn(0f, 1f)
        )

        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(lighter, darker),
                startY = s * 0.1f,
                endY = s * 0.9f
            )
        )
        drawPath(
            path = path,
            color = outline,
            style = Stroke(
                width = max(1.5f, s * 0.055f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        val eyeW = s * 0.075f
        val eyeH = s * 0.19f
        val eyeY = cy - s * 0.09f
        val eyeColor = Color(0xFF111315)
        val eyeCorner = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)

        drawRoundRect(
            color = eyeColor,
            topLeft = Offset(cx - s * 0.115f, eyeY),
            size = Size(eyeW, eyeH),
            cornerRadius = eyeCorner
        )
        drawRoundRect(
            color = eyeColor,
            topLeft = Offset(cx + s * 0.040f, eyeY),
            size = Size(eyeW, eyeH),
            cornerRadius = eyeCorner
        )

        val shR = eyeW * 0.24f
        val shineColor = Color.White.copy(alpha = 0.88f)
        drawCircle(
            color = shineColor,
            radius = shR,
            center = Offset(cx - s * 0.105f + shR, eyeY + eyeH * 0.16f + shR)
        )
        drawCircle(
            color = shineColor,
            radius = shR,
            center = Offset(cx + s * 0.050f + shR, eyeY + eyeH * 0.16f + shR)
        )
    }
}

enum class NanoIconType {
    PLAY, PAUSE, PREV, NEXT, SHUFFLE, REPEAT, REPEAT_ONE,
    LYRICS, FX, EDIT, REFRESH, CLOCK, SAVE, MUSIC, ALBUMS,
    SETTINGS, INFO, SEARCH, CLOSE, BACK, CHEVRON_DOWN
}

private fun polarPoint(cx: Float, cy: Float, radius: Float, angleRad: Double): Offset {
    return Offset(
        x = cx + (radius * cos(angleRad)).toFloat(),
        y = cy + (radius * sin(angleRad)).toFloat()
    )
}

@Composable
fun NanoVectorIcon(
    type: NanoIconType,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val s = minOf(size.width, size.height)
        val strokeW = max(1.6f, s * 0.085f)
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (type) {
                        NanoIconType.PLAY -> {
                val p = Path().apply {
                    moveTo(s * 0.31f, s * 0.20f)
                    lineTo(s * 0.77f, s * 0.50f)
                    lineTo(s * 0.31f, s * 0.80f)
                    close()
                }
                drawPath(p, color = tint)
            }

            NanoIconType.PAUSE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.26f, s * 0.22f),
                    size = Size(s * 0.17f, s * 0.56f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.57f, s * 0.22f),
                    size = Size(s * 0.17f, s * 0.56f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
            NanoIconType.PREV -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.20f, s * 0.24f),
                    size = Size(s * 0.10f, s * 0.52f),
                    cornerRadius = CornerRadius(1.5f, 1.5f)
                )
                val p = Path().apply {
                    moveTo(s * 0.78f, s * 0.23f)
                    lineTo(s * 0.34f, s * 0.50f)
                    lineTo(s * 0.78f, s * 0.77f)
                    close()
                }
                drawPath(p, color = tint)
            }
            NanoIconType.NEXT -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.70f, s * 0.24f),
                    size = Size(s * 0.10f, s * 0.52f),
                    cornerRadius = CornerRadius(1.5f, 1.5f)
                )
                val p = Path().apply {
                    moveTo(s * 0.22f, s * 0.23f)
                    lineTo(s * 0.66f, s * 0.50f)
                    lineTo(s * 0.22f, s * 0.77f)
                    close()
                }
                drawPath(p, color = tint)
            }
            NanoIconType.SHUFFLE -> {
                val p1 = Path().apply {
                    moveTo(s * 0.16f, s * 0.30f)
                    lineTo(s * 0.38f, s * 0.30f)
                    lineTo(s * 0.64f, s * 0.70f)
                    lineTo(s * 0.82f, s * 0.70f)
                }
                val p2 = Path().apply {
                    moveTo(s * 0.16f, s * 0.70f)
                    lineTo(s * 0.38f, s * 0.70f)
                    lineTo(s * 0.64f, s * 0.30f)
                    lineTo(s * 0.82f, s * 0.30f)
                }
                drawPath(p1, color = tint, style = stroke)
                drawPath(p2, color = tint, style = stroke)
                val a1 = Path().apply {
                    moveTo(s * 0.73f, s * 0.21f); lineTo(s * 0.84f, s * 0.30f); lineTo(s * 0.73f, s * 0.39f)
                }
                val a2 = Path().apply {
                    moveTo(s * 0.73f, s * 0.61f); lineTo(s * 0.84f, s * 0.70f); lineTo(s * 0.73f, s * 0.79f)
                }
                drawPath(a1, color = tint, style = stroke)
                drawPath(a2, color = tint, style = stroke)
            }
            NanoIconType.REPEAT, NanoIconType.REPEAT_ONE -> {
                val p1 = Path().apply {
                    moveTo(s * 0.22f, s * 0.45f)
                    lineTo(s * 0.22f, s * 0.30f)
                    lineTo(s * 0.76f, s * 0.30f)
                }
                val p2 = Path().apply {
                    moveTo(s * 0.78f, s * 0.55f)
                    lineTo(s * 0.78f, s * 0.70f)
                    lineTo(s * 0.24f, s * 0.70f)
                }
                drawPath(p1, color = tint, style = stroke)
                drawPath(p2, color = tint, style = stroke)
                val a1 = Path().apply {
                    moveTo(s * 0.68f, s * 0.21f); lineTo(s * 0.78f, s * 0.30f); lineTo(s * 0.68f, s * 0.39f)
                }
                val a2 = Path().apply {
                    moveTo(s * 0.32f, s * 0.61f); lineTo(s * 0.22f, s * 0.70f); lineTo(s * 0.32f, s * 0.79f)
                }
                drawPath(a1, color = tint, style = stroke)
                drawPath(a2, color = tint, style = stroke)
                if (type == NanoIconType.REPEAT_ONE) {
                    drawLine(tint, Offset(s * 0.50f, s * 0.40f), Offset(s * 0.50f, s * 0.60f), strokeW)
                }
            }
            NanoIconType.LYRICS -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.37f, s * 0.16f),
                    size = Size(s * 0.26f, s * 0.42f),
                    cornerRadius = CornerRadius(s * 0.13f, s * 0.13f),
                    style = stroke
                )
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(s * 0.26f, s * 0.30f),
                    size = Size(s * 0.48f, s * 0.40f),
                    style = stroke
                )
                drawLine(tint, Offset(s * 0.50f, s * 0.70f), Offset(s * 0.50f, s * 0.84f), strokeW, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.35f, s * 0.84f), Offset(s * 0.65f, s * 0.84f), strokeW, StrokeCap.Round)
            }
            NanoIconType.FX -> {
                val cols = listOf(0.26f to 0.35f, 0.50f to 0.66f, 0.74f to 0.42f)
                for ((xr, yd) in cols) {
                    drawLine(tint, Offset(s * xr, s * 0.20f), Offset(s * xr, s * 0.80f), strokeW, StrokeCap.Round)
                    drawCircle(tint, radius = s * 0.085f, center = Offset(s * xr, s * yd))
                }
            }
            NanoIconType.EDIT -> {
                drawLine(tint, Offset(s * 0.24f, s * 0.76f), Offset(s * 0.72f, s * 0.24f), strokeW, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.20f, s * 0.80f), Offset(s * 0.34f, s * 0.77f), strokeW, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.65f, s * 0.20f), Offset(s * 0.79f, s * 0.34f), strokeW, StrokeCap.Round)
            }
            NanoIconType.REFRESH -> {
                val pad = s * 0.21f
                val arcSize = s - pad * 2f
                drawArc(
                    color = tint,
                    startAngle = 35f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
                val cx = s * 0.50f
                val cy = s * 0.50f
                val r = arcSize * 0.5f
                val tipAng = Math.toRadians(-35.0)
                val tip = polarPoint(cx, cy, r + s * 0.02f, tipAng)
                val tailIn = polarPoint(cx, cy, r - s * 0.13f, tipAng - 0.32)
                val tailOut = polarPoint(cx, cy, r + s * 0.13f, tipAng - 0.32)
                val head = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(tailIn.x, tailIn.y)
                    lineTo(tailOut.x, tailOut.y)
                    close()
                }
                drawPath(head, color = tint)
            }
            NanoIconType.CLOCK -> {
                drawCircle(tint, radius = s * 0.32f, center = Offset(s * 0.50f, s * 0.50f), style = stroke)
                drawLine(tint, Offset(s * 0.50f, s * 0.32f), Offset(s * 0.50f, s * 0.52f), strokeW, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.50f, s * 0.52f), Offset(s * 0.65f, s * 0.60f), strokeW, StrokeCap.Round)
            }
            NanoIconType.SAVE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.20f, s * 0.20f),
                    size = Size(s * 0.60f, s * 0.60f),
                    cornerRadius = CornerRadius(3f, 3f),
                    style = stroke
                )
                drawRect(tint, Offset(s * 0.34f, s * 0.20f), Size(s * 0.32f, s * 0.20f), style = stroke)
                drawRect(tint, Offset(s * 0.32f, s * 0.54f), Size(s * 0.36f, s * 0.26f), style = stroke)
            }
            NanoIconType.MUSIC -> {
                drawLine(tint, Offset(s * 0.38f, s * 0.72f), Offset(s * 0.38f, s * 0.26f), strokeW, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.38f, s * 0.26f), Offset(s * 0.76f, s * 0.20f), strokeW, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.76f, s * 0.20f), Offset(s * 0.76f, s * 0.64f), strokeW, StrokeCap.Round)
                drawCircle(tint, radius = s * 0.10f, center = Offset(s * 0.30f, s * 0.74f))
                drawCircle(tint, radius = s * 0.10f, center = Offset(s * 0.68f, s * 0.66f))
            }
            NanoIconType.ALBUMS -> {
                val rects = listOf(0.18f to 0.18f, 0.54f to 0.18f, 0.18f to 0.54f, 0.54f to 0.54f)
                for ((rx, ry) in rects) {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(s * rx, s * ry),
                        size = Size(s * 0.28f, s * 0.28f),
                        cornerRadius = CornerRadius(3f, 3f),
                        style = stroke
                    )
                }
            }
            NanoIconType.SETTINGS -> {
                val cx = s * 0.50f
                val cy = s * 0.50f
                val rRoot = s * 0.305f
                val rTip = s * 0.425f
                val rHole = s * 0.172f
                val daBase = 0.215
                val daTip = 0.118
                val filletA = 0.042
                val dr = s * 0.028f

                val gear = Path().apply {
                    fillType = PathFillType.EvenOdd
                    for (i in 0 until 8) {
                        val a = -PI / 2.0 + i * (PI / 4.0)
                        val pVIn = polarPoint(cx, cy, rRoot, a - daBase - filletA)
                        val pB1Ctrl = polarPoint(cx, cy, rRoot, a - daBase)
                        val pB1Out = polarPoint(cx, cy, rRoot + dr, a - daBase + 0.022)
                        val pT1In = polarPoint(cx, cy, rTip - dr * 0.8f, a - daTip - 0.022)
                        val pT1Ctrl = polarPoint(cx, cy, rTip, a - daTip)
                        val pT1Top = polarPoint(cx, cy, rTip, a - daTip * 0.48)
                        val pT2Top = polarPoint(cx, cy, rTip, a + daTip * 0.48)
                        val pT2Ctrl = polarPoint(cx, cy, rTip, a + daTip)
                        val pT2Out = polarPoint(cx, cy, rTip - dr * 0.8f, a + daTip + 0.022)
                        val pB2In = polarPoint(cx, cy, rRoot + dr, a + daBase - 0.022)
                        val pB2Ctrl = polarPoint(cx, cy, rRoot, a + daBase)
                        val pVOut = polarPoint(cx, cy, rRoot, a + daBase + filletA)

                        if (i == 0) {
                            moveTo(pVIn.x, pVIn.y)
                        } else {
                            val aPrev = -PI / 2.0 + (i - 1) * (PI / 4.0)
                            val aMid = 0.5 * ((aPrev + daBase + filletA) + (a - daBase - filletA))
                            val pArcMid = polarPoint(cx, cy, rRoot, aMid)
                            quadraticTo(pArcMid.x, pArcMid.y, pVIn.x, pVIn.y)
                        }

                        quadraticTo(pB1Ctrl.x, pB1Ctrl.y, pB1Out.x, pB1Out.y)
                        lineTo(pT1In.x, pT1In.y)
                        quadraticTo(pT1Ctrl.x, pT1Ctrl.y, pT1Top.x, pT1Top.y)
                        lineTo(pT2Top.x, pT2Top.y)
                        quadraticTo(pT2Ctrl.x, pT2Ctrl.y, pT2Out.x, pT2Out.y)
                        lineTo(pB2In.x, pB2In.y)
                        quadraticTo(pB2Ctrl.x, pB2Ctrl.y, pVOut.x, pVOut.y)
                    }

                    val aLast = -PI / 2.0 + 7.0 * (PI / 4.0)
                    val aFirst = -PI / 2.0 + 2.0 * PI
                    val aMidClose = 0.5 * ((aLast + daBase + filletA) + (aFirst - daBase - filletA))
                    val pCloseMid = polarPoint(cx, cy, rRoot, aMidClose)
                    val pCloseEnd = polarPoint(cx, cy, rRoot, aFirst - daBase - filletA)
                    quadraticTo(pCloseMid.x, pCloseMid.y, pCloseEnd.x, pCloseEnd.y)
                    close()

                    addOval(Rect(cx - rHole, cy - rHole, cx + rHole, cy + rHole))
                }
                drawPath(gear, color = tint)
            }
            NanoIconType.INFO -> {
                val ringStroke = max(1.5f, s * 0.080f)
                drawCircle(
                    color = tint,
                    radius = s * 0.34f,
                    center = Offset(s * 0.50f, s * 0.50f),
                    style = Stroke(width = ringStroke)
                )
                drawCircle(
                    color = tint,
                    radius = s * 0.048f,
                    center = Offset(s * 0.50f, s * 0.315f)
                )
                drawLine(
                    color = tint,
                    start = Offset(s * 0.50f, s * 0.485f),
                    end = Offset(s * 0.50f, s * 0.685f),
                    strokeWidth = max(1.6f, s * 0.084f),
                    cap = StrokeCap.Round
                )
            }
            NanoIconType.SEARCH -> {
                drawCircle(tint, radius = s * 0.25f, center = Offset(s * 0.44f, s * 0.44f), style = stroke)
                drawLine(tint, Offset(s * 0.62f, s * 0.62f), Offset(s * 0.80f, s * 0.80f), strokeW, StrokeCap.Round)
            }
            NanoIconType.CLOSE -> {
                drawLine(tint, Offset(s * 0.26f, s * 0.26f), Offset(s * 0.74f, s * 0.74f), strokeW, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.74f, s * 0.26f), Offset(s * 0.26f, s * 0.74f), strokeW, StrokeCap.Round)
            }
            NanoIconType.BACK -> {
                val p = Path().apply {
                    moveTo(s * 0.64f, s * 0.22f)
                    lineTo(s * 0.34f, s * 0.50f)
                    lineTo(s * 0.64f, s * 0.78f)
                }
                drawPath(p, color = tint, style = stroke)
            }
            NanoIconType.CHEVRON_DOWN -> {
                val p = Path().apply {
                    moveTo(s * 0.22f, s * 0.36f)
                    lineTo(s * 0.50f, s * 0.66f)
                    lineTo(s * 0.78f, s * 0.36f)
                }
                drawPath(p, color = tint, style = stroke)
            }
        }
    }
}