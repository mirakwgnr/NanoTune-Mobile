package com.nanotune.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

object LyricsParser {

    private val META_TAG_RE = Regex("^\\[(ar|ti|al|by|offset|length|re|ve):.*\\]$", RegexOption.IGNORE_CASE)
    private val LRC_LINE_RE = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val ELRC_WORD_RE = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    private val VOICE_PREFIX_RE = Regex("^\\s*v\\d+\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun hasWordTimestamps(raw: String): Boolean = ELRC_WORD_RE.containsMatchIn(raw)

    fun findSiblingLrcFile(audioFile: File): File? {
        val parent = audioFile.parentFile ?: return null
        val stem = audioFile.nameWithoutExtension
        for (ext in listOf("elrc", "lrc")) {
            val direct = File(parent, "$stem.$ext")
            if (direct.exists() && direct.isFile) return direct
            val sub = File(File(parent, "lyrics"), "$stem.$ext")
            if (sub.exists() && sub.isFile) return sub
        }
        return null
    }

    suspend fun loadLyricsForTrack(context: Context, track: TrackItem): ParsedLyrics =
        withContext(Dispatchers.IO) {
            var raw = ""
            var source = "No Lyrics Found"

            if (track.path.isNotEmpty()) {
                val sibling = findSiblingLrcFile(File(track.path))
                if (sibling != null) {
                    val text = runCatching { sibling.readText(Charsets.UTF_8) }.getOrDefault("").trim()
                    if (text.isNotEmpty()) {
                        raw = text
                        val tag = if (sibling.extension.equals("elrc", ignoreCase = true)) ".ELRC" else ".LRC"
                        source = "$tag (${sibling.name})"
                    }
                }
            }

            if (raw.isEmpty()) {
                val localDir = File(context.filesDir, "lyrics")
                for (ext in listOf("elrc", "lrc")) {
                    val f = File(localDir, "${track.id}.$ext")
                    if (f.exists()) {
                        val txt = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("").trim()
                        if (txt.isNotEmpty()) {
                            raw = txt
                            source = ".${ext.uppercase()} (Local)"
                            break
                        }
                    }
                }
            }

            if (raw.isEmpty() && track.folderTreeUri.isNotEmpty()) {
                runCatching {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(track.folderTreeUri))
                    val stem = track.fileName.substringBeforeLast('.')
                    val foundDoc = tree?.let { findDocSiblingLrc(it, stem) }
                    if (foundDoc != null) {
                        context.contentResolver.openInputStream(foundDoc.uri)?.bufferedReader()?.use {
                            val txt = it.readText().trim()
                            if (txt.isNotEmpty()) {
                                raw = txt
                                val isElrc = foundDoc.name?.lowercase()?.endsWith(".elrc") == true
                                source = "${if (isElrc) ".ELRC" else ".LRC"} (${foundDoc.name})"
                            }
                        }
                    }
                }
            }

            if (raw.isEmpty()) {
                val ext = track.fileName.substringAfterLast('.', "").lowercase()
                when (ext) {
                    "flac" -> {
                        val flacLyr = MusicScanner.readFlacLyrics(context, track.uri)
                        if (!flacLyr.isNullOrBlank()) {
                            raw = flacLyr.trim()
                            source = "FLAC Embedded"
                        }
                    }
                    "mp3" -> {
                        val uslt = runCatching {
                            context.contentResolver.openInputStream(track.uri)?.use { stream ->
                                MusicScanner.extractMp3UsltLyrics(BufferedInputStream(stream))
                            }
                        }.getOrNull()
                        if (!uslt.isNullOrBlank()) {
                            raw = uslt.trim()
                            source = "MP3 Embedded (USLT)"
                        }
                    }
                    "m4a" -> {
                        val m4a = runCatching {
                            context.contentResolver.openInputStream(track.uri)?.use { stream ->
                                MusicScanner.extractM4aLyrics(BufferedInputStream(stream))
                            }
                        }.getOrNull()
                        if (!m4a.isNullOrBlank()) {
                            raw = m4a.trim()
                            source = "M4A Embedded (©lyr)"
                        }
                    }
                }
            }

            parseLrcText(raw, source)
        }

    private fun findDocSiblingLrc(dir: DocumentFile, stem: String): DocumentFile? {
        for (f in dir.listFiles()) {
            if (f.isFile) {
                val n = f.name.orEmpty()
                if (n.equals("$stem.elrc", ignoreCase = true) || n.equals("$stem.lrc", ignoreCase = true)) {
                    return f
                }
            } else if (f.isDirectory && f.name.equals("lyrics", ignoreCase = true)) {
                findDocSiblingLrc(f, stem)?.let { return it }
            }
        }
        return null
    }

    suspend fun saveLrcForTrack(context: Context, track: TrackItem, rawText: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val isElrc = hasWordTimestamps(rawText) ||
                    (track.path.isNotEmpty() && File(track.path).let {
                        File(it.parentFile, "${it.nameWithoutExtension}.elrc").exists()
                    })
                val ext = if (isElrc) "elrc" else "lrc"
                val stem = track.fileName.substringBeforeLast('.')
                val targetName = "$stem.$ext"

                if (track.folderTreeUri.isNotEmpty()) {
                    val root = DocumentFile.fromTreeUri(context, Uri.parse(track.folderTreeUri))
                        ?: error("Unable to access folder URI")
                    val existing = root.findFile(targetName)
                    val doc = existing ?: root.createFile("text/plain", targetName)
                        ?: error("Could not create $targetName in folder")
                    context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                        out.write(rawText.toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot write to $targetName")
                    return@runCatching "${ext.uppercase()} ($targetName)"
                }

                if (track.path.isNotEmpty()) {
                    val audioFile = File(track.path)
                    val outFile = File(audioFile.parentFile, targetName)
                    val wroteDirect = runCatching {
                        outFile.writeText(rawText, Charsets.UTF_8)
                        true
                    }.getOrDefault(false)
                    if (wroteDirect) {
                        return@runCatching "${ext.uppercase()} (${outFile.name})"
                    }
                }

                val localDir = File(context.filesDir, "lyrics").apply { mkdirs() }
                val outFile = File(localDir, "${track.id}.$ext")
                outFile.writeText(rawText, Charsets.UTF_8)
                "App Storage (${outFile.name})"
            }
        }

    fun parseLrcText(rawText: String, sourceLabel: String = ""): ParsedLyrics {
        if (rawText.isBlank()) {
            return ParsedLyrics(
                isSynced = false,
                hasElrcWords = false,
                lines = emptyList(),
                rawText = "",
                sourceLabel = sourceLabel.ifEmpty { "No Lyrics Found" }
            )
        }

        val synced = mutableListOf<MutableLine>()
        val plain = mutableListOf<LyricLine>()

        for (rawLine in rawText.lines()) {
            val trimmed = rawLine.trim()
            if (META_TAG_RE.matches(trimmed)) continue

            val lineMatches = LRC_LINE_RE.findAll(rawLine).toList()
            val hasWords = ELRC_WORD_RE.containsMatchIn(rawLine)

            if (lineMatches.isNotEmpty()) {
                val lyricBody = LRC_LINE_RE.replace(rawLine, "").trim()
                for (m in lineMatches) {
                    val tMs = tsToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    val (words, cleanTxt, _) = parseElrcWords(lyricBody, tMs)
                    synced.add(MutableLine(tMs, cleanTxt.ifEmpty { "♪" }, words))
                }
            } else if (hasWords) {
                val (words, cleanTxt, inferredMs) = parseElrcWords(rawLine, -1L)
                if (words.isNotEmpty()) {
                    synced.add(MutableLine(inferredMs, cleanTxt.ifEmpty { "♪" }, words))
                }
            } else {
                val clean = VOICE_PREFIX_RE.replace(rawLine, "")
                plain.add(LyricLine(-1L, clean, emptyList()))
            }
        }

        if (synced.isNotEmpty()) {
            synced.sortBy { it.timeMs }
            val finalized = synced.mapIndexed { i, entry ->
                val nextLineMs = if (i + 1 < synced.size) {
                    synced[i + 1].timeMs
                } else {
                    (entry.words.lastOrNull()?.startMs ?: entry.timeMs) + 1200L
                }
                val fixedWords = entry.words.mapIndexed { j, w ->
                    val end = when {
                        w.endMs >= 0L -> w.endMs
                        j + 1 < entry.words.size -> entry.words[j + 1].startMs
                        else -> max(w.startMs + 250L, nextLineMs)
                    }
                    LyricWord(w.text, w.startMs, max(w.startMs + 40L, end))
                }
                LyricLine(entry.timeMs, entry.text, fixedWords)
            }
            val anyWords = finalized.any { it.words.isNotEmpty() }
            return ParsedLyrics(
                isSynced = true,
                hasElrcWords = anyWords,
                lines = finalized,
                rawText = rawText,
                sourceLabel = sourceLabel
            )
        }

        return ParsedLyrics(
            isSynced = false,
            hasElrcWords = false,
            lines = plain,
            rawText = rawText,
            sourceLabel = sourceLabel
        )
    }

    private data class MutableWord(val text: String, val startMs: Long, var endMs: Long)
    private data class MutableLine(val timeMs: Long, val text: String, val words: List<MutableWord>)

    private fun tsToMs(mins: String, secs: String, frac: String): Long {
        val fracPadded = frac.ifEmpty { "0" }.padEnd(3, '0').take(3)
        return (mins.toLong() * 60L + secs.toLong()) * 1000L + fracPadded.toLong()
    }

    private fun parseElrcWords(body: String, fallbackStartMs: Long): Triple<List<MutableWord>, String, Long> {
        val bodyClean = VOICE_PREFIX_RE.replace(body, "").trim()
        val matches = ELRC_WORD_RE.findAll(bodyClean).toList()
        if (matches.isEmpty()) {
            return Triple(emptyList(), bodyClean, fallbackStartMs)
        }

        val words = mutableListOf<MutableWord>()
        val firstTs = tsToMs(matches[0].groupValues[1], matches[0].groupValues[2], matches[0].groupValues[3])
        val lineStart = if (fallbackStartMs >= 0L) fallbackStartMs else firstTs

        if (matches[0].range.first > 0) {
            val leadTxt = VOICE_PREFIX_RE.replace(bodyClean.substring(0, matches[0].range.first), "")
            if (leadTxt.isNotBlank()) {
                words.add(MutableWord(leadTxt, lineStart, firstTs))
            }
        }

        for (idx in matches.indices) {
            val m = matches[idx]
            val tMs = tsToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val segStart = m.range.last + 1
            val segEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else bodyClean.length
            val segTxt = bodyClean.substring(segStart, segEnd)

            if (segTxt.isBlank()) {
                if (words.isNotEmpty() && words.last().endMs < 0L) {
                    words.last().endMs = tMs
                }
                continue
            }
            if (words.isNotEmpty() && words.last().endMs < 0L) {
                words.last().endMs = tMs
            }
            words.add(MutableWord(segTxt, tMs, -1L))
        }

        val joined = words.joinToString("") { it.text }.trim().ifEmpty { "♪" }
        val resolvedStart = if (fallbackStartMs >= 0L) fallbackStartMs else firstTs
        return Triple(words, joined, resolvedStart)
    }
}

private data class MeasuredWordItem(
    val word: LyricWord,
    val xPx: Float,
    val yOffsetPx: Float,
    val layout: TextLayoutResult
)

private data class MeasuredLyricEntry(
    val line: LyricLine,
    val yPx: Float,
    val heightPx: Float,
    val plainLayout: TextLayoutResult,
    val wordItems: List<MeasuredWordItem>
)

/**
 * High-FPS (60-120+ Hz) animated .ELRC word-by-word karaoke & .LRC lyric renderer.
 * Features native Android VelocityTracker inertial fling decay, 3.5s post-fling manual
 * browse pause, exponential spring auto-scroll, 1.10x active line scale + accent glow,
 * .ELRC word sine-wave bounce, and tap-to-seek.
 */
@Composable
fun AnimatedLyricsCanvas(
    parsedLyrics: ParsedLyrics,
    positionMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    fontSizeSp: Float,
    fontFamily: FontFamily,
    accentColor: Color,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val lines = parsedLyrics.lines

    val emptyMessage = "No embedded FLAC, ID3, MP4, .LRC, or .ELRC lyrics found for this track.\nTap 'Edit Lyrics' to add or sync lyrics."
    val displayLines = remember(parsedLyrics) {
        if (lines.isEmpty()) listOf(LyricLine(-1L, emptyMessage, emptyList())) else lines
    }

    val baseStyle = remember(fontSizeSp, fontFamily) {
        TextStyle(
            fontFamily = fontFamily,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = (fontSizeSp * 1.28f).sp
        )
    }

    val padXPx = with(density) { 20.dp.toPx() }
    val lineSpacingPx = with(density) { 18.dp.toPx() }
    val bounceAmpPx = with(density) { 2.dp.toPx() }

    val measuredEntries = remember(displayLines, canvasSize.width, baseStyle) {
        if (canvasSize.width <= 0) return@remember emptyList()
        val wrapW = max(120, ((canvasSize.width - padXPx * 2f) / 1.12f).toInt())
        var y = with(density) { 28.dp.toPx() }

        displayLines.map { entry ->
            val plainLayout = textMeasurer.measure(
                text = AnnotatedString(entry.text.ifEmpty { "♪" }),
                style = baseStyle,
                constraints = Constraints(maxWidth = wrapW)
            )
            val wordItems = mutableListOf<MeasuredWordItem>()
            var hPx = max(with(density) { 28.dp.toPx() }, plainLayout.size.height.toFloat() + 8f)

            if (entry.words.isNotEmpty()) {
                var curX = 0f
                var rowIdx = 0
                val rowH = plainLayout.getLineBottom(0) - plainLayout.getLineTop(0)
                for (w in entry.words) {
                    val measured = textMeasurer.measure(
                        text = AnnotatedString(w.text),
                        style = baseStyle
                    )
                    val wPx = measured.size.width.toFloat()
                    val textToDraw = if (wordItems.isNotEmpty() && curX + wPx > wrapW && w.text.isNotBlank()) {
                        rowIdx++
                        curX = 0f
                        w.text.trimStart()
                    } else {
                        w.text
                    }
                    val finalLayout = if (textToDraw == w.text) measured else {
                        textMeasurer.measure(AnnotatedString(textToDraw), style = baseStyle)
                    }
                    wordItems.add(
                        MeasuredWordItem(
                            word = w,
                            xPx = curX,
                            yOffsetPx = rowIdx * rowH,
                            layout = finalLayout
                        )
                    )
                    curX += finalLayout.size.width.toFloat()
                }
                hPx = max(with(density) { 28.dp.toPx() }, (rowIdx + 1) * rowH + 8f)
            }

            val item = MeasuredLyricEntry(
                line = entry,
                yPx = y,
                heightPx = hPx,
                plainLayout = plainLayout,
                wordItems = wordItems
            )
            y += hPx + lineSpacingPx
            item
        }
    }

    val maxScrollY = remember(measuredEntries, canvasSize.height) {
        if (measuredEntries.isEmpty() || canvasSize.height <= 0) {
            0f
        } else {
            val last = measuredEntries.last()
            max(0f, last.yPx + last.heightPx + with(density) { 48.dp.toPx() } - canvasSize.height)
        }
    }

    val activeIdx = remember(displayLines, positionMs, parsedLyrics.isSynced) {
        if (!parsedLyrics.isSynced || displayLines.isEmpty()) {
            -1
        } else {
            var found = -1
            for (i in displayLines.indices) {
                if (positionMs >= displayLines[i].timeMs) found = i else break
            }
            found
        }
    }

    val scrollAnimatable = remember(parsedLyrics) { Animatable(0f) }
    var manualBrowseUntil by remember(parsedLyrics) { mutableLongStateOf(0L) }
    var isDragging by remember(parsedLyrics) { mutableStateOf(false) }
    var isFlinging by remember(parsedLyrics) { mutableStateOf(false) }

    LaunchedEffect(parsedLyrics, maxScrollY) {
        scrollAnimatable.updateBounds(0f, maxScrollY)
    }

    val focusStates = remember(displayLines) {
        mutableStateListOf<Float>().apply {
            repeat(displayLines.size) { add(if (parsedLyrics.isSynced) 0f else 0.55f) }
        }
    }

    var clockAnchorMs by remember(parsedLyrics) { mutableLongStateOf(positionMs) }
    var clockAnchorNanos by remember(parsedLyrics) { mutableLongStateOf(System.nanoTime()) }
    var smoothPosMs by remember(parsedLyrics) { mutableLongStateOf(positionMs) }

    LaunchedEffect(positionMs, isPlaying, playbackSpeed) {
        val now = System.nanoTime()
        val est = if (isPlaying) {
            clockAnchorMs + ((now - clockAnchorNanos) / 1_000_000.0 * playbackSpeed).toLong()
        } else {
            clockAnchorMs
        }
        if (!isPlaying || abs(positionMs - est) > 95L) {
            clockAnchorMs = positionMs
            clockAnchorNanos = now
            smoothPosMs = positionMs
        }
    }

    LaunchedEffect(parsedLyrics, isPlaying, activeIdx, maxScrollY) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.002f, 0.05f)
            lastNanos = frameNanos

            val scrollAlpha = 1f - exp(-10f * dt)
            val focusAlpha = 1f - exp(-13f * dt)

            for (i in focusStates.indices) {
                val targetF = if (parsedLyrics.isSynced) {
                    if (i == activeIdx) 1f else 0f
                } else 0.55f
                val df = targetF - focusStates[i]
                if (abs(df) > 0.006f) {
                    focusStates[i] = focusStates[i] + df * focusAlpha
                } else {
                    focusStates[i] = targetF
                }
            }

            smoothPosMs = if (isPlaying && parsedLyrics.hasElrcWords) {
                clockAnchorMs + ((frameNanos - clockAnchorNanos) / 1_000_000.0 * playbackSpeed).toLong()
            } else {
                positionMs
            }

            if (!isDragging && !isFlinging && SystemClock.uptimeMillis() >= manualBrowseUntil) {
                if (parsedLyrics.isSynced && activeIdx in measuredEntries.indices) {
                    val entry = measuredEntries[activeIdx]
                    val centerY = entry.yPx + entry.heightPx * 0.5f
                    val desiredTarget = (centerY - canvasSize.height * 0.38f).coerceIn(0f, maxScrollY)
                    val cur = scrollAnimatable.value
                    val diff = desiredTarget - cur
                    if (abs(diff) > 0.25f) {
                        scrollAnimatable.snapTo((cur + diff * scrollAlpha).coerceIn(0f, maxScrollY))
                    } else if (diff != 0f) {
                        scrollAnimatable.snapTo(desiredTarget)
                    }
                }
            }
        }
    }

    val velocityTracker = remember { VelocityTracker() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(parsedLyrics, maxScrollY) {
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        manualBrowseUntil = Long.MAX_VALUE
                        velocityTracker.resetTracking()
                        coroutineScope.launch {
                            scrollAnimatable.stop()
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        val velocityY = velocityTracker.calculateVelocity().y
                        coroutineScope.launch {
                            isFlinging = true
                            try {
                                scrollAnimatable.animateDecay(
                                    initialVelocity = -velocityY,
                                    animationSpec = exponentialDecay(frictionMultiplier = 1.6f)
                                )
                            } finally {
                                isFlinging = false
                                if (!isDragging) {
                                    manualBrowseUntil = SystemClock.uptimeMillis() + 3500L
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        isFlinging = false
                        manualBrowseUntil = SystemClock.uptimeMillis() + 3500L
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        manualBrowseUntil = Long.MAX_VALUE
                        coroutineScope.launch {
                            val next = (scrollAnimatable.value - dragAmount).coerceIn(0f, maxScrollY)
                            scrollAnimatable.snapTo(next)
                        }
                    }
                )
            }
            .pointerInput(measuredEntries, parsedLyrics) {
                detectTapGestures { offset ->
                    if (!parsedLyrics.isSynced) return@detectTapGestures
                    val yTap = offset.y + scrollAnimatable.value
                    val hit = measuredEntries.firstOrNull {
                        yTap >= it.yPx - 10f && yTap <= it.yPx + it.heightPx + 10f && it.line.timeMs >= 0L
                    }
                    if (hit != null) {
                        isDragging = false
                        isFlinging = false
                        manualBrowseUntil = 0L
                        coroutineScope.launch {
                            scrollAnimatable.stop()
                        }
                        val targetMs = hit.line.words.firstOrNull()?.startMs ?: hit.line.timeMs
                        clockAnchorMs = targetMs
                        clockAnchorNanos = System.nanoTime()
                        smoothPosMs = targetMs
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSeekToMs(targetMs)
                    }
                }
            }
    ) {
        val h = size.height
        val w = size.width
        if (w <= 0f || h <= 0f) return@Canvas

        val scrollY = scrollAnimatable.value

        for (i in measuredEntries.indices) {
            val item = measuredEntries[i]
            val drawY = item.yPx - scrollY
            val lineH = item.heightPx
            if (drawY + lineH < -60f || drawY > h + 60f) continue

            val focus = focusStates.getOrElse(i) { 0f }
            val edgeFade = when {
                drawY < 36f -> ((drawY + 24f) / 60f).coerceIn(0f, 1f)
                drawY + lineH > h - 30f -> ((h - drawY) / max(1f, lineH + 30f)).coerceIn(0f, 1f)
                else -> 1f
            }

            val lineScale = if (parsedLyrics.isSynced) 1f + 0.10f * focus else 1f
            val pivot = Offset(padXPx, drawY + lineH * 0.5f)

            scale(scaleX = lineScale, scaleY = lineScale, pivot = pivot) {
                if (parsedLyrics.isSynced && item.wordItems.isNotEmpty() && i == activeIdx) {
                    val topY = drawY + 2f
                    for (wi in item.wordItems) {
                        val st = wi.word.startMs
                        val en = wi.word.endMs
                        val wordColor: Color
                        val liftPx: Float
                        var shadow: Shadow? = null

                        when {
                            smoothPosMs >= en -> {
                                wordColor = Color.White.copy(alpha = edgeFade)
                                liftPx = 0f
                            }
                            smoothPosMs in st until en -> {
                                val prog = ((smoothPosMs - st).toFloat() / max(1f, (en - st).toFloat())).coerceIn(0f, 1f)
                                liftPx = -bounceAmpPx * sin(prog * PI).toFloat()
                                wordColor = lerp(accentColor, Color.White, 0.45f).copy(alpha = edgeFade)
                                shadow = Shadow(
                                    color = accentColor.copy(alpha = 0.65f * edgeFade),
                                    offset = Offset(1.5f, 1.5f),
                                    blurRadius = 12f
                                )
                            }
                            else -> {
                                wordColor = Color.White.copy(alpha = 0.45f * edgeFade)
                                liftPx = 0f
                            }
                        }

                        drawText(
                            textLayoutResult = wi.layout,
                            color = wordColor,
                            topLeft = Offset(padXPx + wi.xPx, topY + wi.yOffsetPx + liftPx),
                            shadow = shadow
                        )
                    }
                } else {
                    val textColor: Color
                    val shadow: Shadow?
                    if (parsedLyrics.isSynced) {
                        val dist = abs(i - activeIdx).coerceAtMost(6)
                        val baseAlpha = when {
                            i == activeIdx -> 1f
                            i < activeIdx -> (0.38f - dist * 0.03f).coerceAtLeast(0.20f)
                            else -> (0.55f - dist * 0.04f).coerceAtLeast(0.24f)
                        }
                        val alpha = ((baseAlpha + (1f - baseAlpha) * focus) * edgeFade).coerceIn(0f, 1f)
                        textColor = if (focus > 0.72f) {
                            Color.White.copy(alpha = alpha)
                        } else {
                            lerp(Color(0xFFD6E0DE), accentColor, focus * 0.35f).copy(alpha = alpha)
                        }
                        shadow = if (focus > 0.20f) {
                            Shadow(
                                color = accentColor.copy(alpha = 0.50f * focus * edgeFade),
                                offset = Offset(1.5f, 1.5f),
                                blurRadius = 14f * focus
                            )
                        } else null
                    } else {
                        textColor = Color(0xFFE8F0EE).copy(alpha = 0.90f * edgeFade)
                        shadow = null
                    }

                    drawText(
                        textLayoutResult = item.plainLayout,
                        color = textColor,
                        topLeft = Offset(padXPx, drawY + 2f),
                        shadow = shadow
                    )
                }
            }
        }
    }
}

/**
 * Built-in Lyrics & Live Timestamp Sync Editor modal with 'Stamp Current Time'
 * & .LRC/.ELRC save.
 */
@Composable
fun LyricsEditorModal(
    initialRawText: String,
    currentPositionMs: Long,
    accent: Color,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var fieldValue by remember(initialRawText) {
        mutableStateOf(TextFieldValue(initialRawText, TextRange(0)))
    }
    val onAccentText = if (ColorExtractor.luminance(accent) > 0.45f) {
        Color(0xFF07100B)
    } else {
        Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9070A0B))
            .padding(horizontal = 16.dp, vertical = 28.dp)
    ) {
        NanoGlassCard(
            cornerRadius = 18.dp,
            accentGlow = true,
            darkAlpha = 0.92f,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "EDIT / SYNC LYRICS",
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = "Paste plain lyrics, .LRC, or .ELRC and stamp timestamps live",
                            color = Color(0xFFAFC0BE),
                            fontSize = 12.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        NanoVectorIcon(
                            type = NanoIconType.CLOSE,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    placeholder = {
                        Text(
                            "Paste plain lyrics, [mm:ss.xx] .LRC, or <mm:ss.xxx> .ELRC...",
                            color = Color(0xFF708280)
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color(0xFFEAF0EE)
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                        focusedContainerColor = Color(0xD80A0E0F),
                        unfocusedContainerColor = Color(0xD80A0E0F),
                        cursorColor = accent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditorPillButton(
                        text = "Stamp Current Time",
                        icon = NanoIconType.CLOCK,
                        filled = false,
                        accent = accent,
                        contentColor = Color(0xFFEAF2F0),
                        onClick = {
                            val pos = currentPositionMs.coerceAtLeast(0L)
                            val mins = pos / 60000L
                            val secs = (pos % 60000L) / 1000L
                            val cs = (pos % 1000L) / 10L
                            val stamp = "[%02d:%02d.%02d] ".format(mins, secs, cs)

                            val text = fieldValue.text
                            val curPos = fieldValue.selection.start.coerceIn(0, text.length)
                            val lineStart = text.lastIndexOf('\n', (curPos - 1).coerceAtLeast(0)).let {
                                if (it < 0) 0 else it + 1
                            }
                            val updated = buildString {
                                append(text.substring(0, lineStart))
                                append(stamp)
                                append(text.substring(lineStart))
                            }
                            val nextLineIdx = updated.indexOf('\n', lineStart + stamp.length)
                            val nextCursor = if (nextLineIdx >= 0) {
                                (nextLineIdx + 1).coerceAtMost(updated.length)
                            } else {
                                updated.length
                            }
                            fieldValue = TextFieldValue(updated, TextRange(nextCursor))
                        },
                        modifier = Modifier.weight(1f)
                    )

                    EditorPillButton(
                        text = "Save .LRC",
                        icon = NanoIconType.SAVE,
                        filled = true,
                        accent = accent,
                        contentColor = onAccentText,
                        onClick = { onSave(fieldValue.text.trim()) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorPillButton(
    text: String,
    icon: NanoIconType,
    filled: Boolean,
    accent: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (filled) accent else Color.White.copy(alpha = 0.09f)
    val borderColor = if (filled) accent else Color.White.copy(alpha = 0.16f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        NanoVectorIcon(type = icon, tint = contentColor, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (filled) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}