package com.nanotune.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

enum class NavTab(val label: String, val icon: NanoIconType) {
    TRACKS("Tracks", NanoIconType.MUSIC),
    ALBUMS("Albums", NanoIconType.ALBUMS),
    LYRICS("Lyrics", NanoIconType.LYRICS),
    SETTINGS("Settings", NanoIconType.SETTINGS),
    ABOUT("About", NanoIconType.INFO)
}

@Composable
fun NanoIconButton(
    icon: NanoIconType,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        NanoVectorIcon(
            type = icon,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun NanoPillButton(
    text: String,
    icon: NanoIconType? = null,
    filled: Boolean,
    accent: Color,
    contentColor: Color = if (filled) {
        if (ColorExtractor.luminance(accent) > 0.45f) Color(0xFF07100B) else Color.White
    } else {
        Color(0xFFEAF2F0)
    },
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
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            NanoVectorIcon(type = icon, tint = contentColor, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
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

@Composable
fun NanoSearchField(
    query: String,
    placeholder: String,
    accent: Color,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                text = placeholder,
                color = Color(0xFF7C8D8B),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            NanoVectorIcon(
                type = NanoIconType.SEARCH,
                tint = Color(0xFF9EB0AD),
                modifier = Modifier.size(16.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                NanoIconButton(
                    icon = NanoIconType.CLOSE,
                    tint = Color(0xFF9EB0AD),
                    onClick = { onQueryChange("") },
                    size = 28.dp,
                    iconSize = 14.dp
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color(0xFFEAF0EE),
            unfocusedTextColor = Color(0xFFEAF0EE),
            focusedBorderColor = accent,
            unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
            focusedContainerColor = Color(0xD80C1113),
            unfocusedContainerColor = Color(0xD80C1113),
            cursorColor = accent
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun RoundedCoverArt(
    bitmap: Bitmap?,
    accent: Color,
    cornerRadius: Dp = 12.dp,
    borderWidth: Dp = 2.dp,
    modifier: Modifier = Modifier
) {
    val fallback = remember(accent) {
        ColorExtractor.buildFallbackCoverBitmap(accent, 256)
    }
    val imgBmp = remember(bitmap, fallback) { (bitmap ?: fallback).asImageBitmap() }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .border(borderWidth, accent.copy(alpha = 0.65f), shape)
            .background(Color(0xFF151A1C))
    ) {
        Image(
            bitmap = imgBmp,
            contentDescription = "Album Art",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun TrackThumbnailArt(
    track: TrackItem,
    accent: Color,
    borderWidth: Dp = 1.dp,
    cornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cacheKey = track.coverHash.ifEmpty { track.uri.toString() }
    var thumb by remember(cacheKey) {
        mutableStateOf(ColorExtractor.getCachedCover(cacheKey))
    }

    LaunchedEffect(cacheKey) {
        if (thumb == null) {
            val (bmp, _) = ColorExtractor.loadTrackArtwork(context, track, preferLarge = false)
            thumb = bmp
        }
    }

    RoundedCoverArt(
        bitmap = thumb,
        accent = accent,
        cornerRadius = cornerRadius,
        borderWidth = borderWidth,
        modifier = modifier
    )
}

@Composable
fun AlbumCollageCover(
    album: AlbumGroup,
    accent: Color,
    cornerRadius: Dp = 10.dp,
    borderWidth: Dp = 1.5.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hashes = remember(album.albumKey, album.coverHashes) { album.coverHashes.take(4) }
    var bitmaps by remember(album.albumKey, hashes) {
        mutableStateOf<List<ImageBitmap>>(
            hashes.mapNotNull { h ->
                ColorExtractor.getCachedLargeCover(h)?.asImageBitmap()
            }
        )
    }

    LaunchedEffect(album.albumKey, hashes) {
        if (hashes.isNotEmpty()) {
            val loaded = hashes.mapNotNull { h ->
                ColorExtractor.loadCachedBitmapByHash(context, h, large = true)?.asImageBitmap()
            }
            if (loaded.isNotEmpty()) {
                bitmaps = loaded
                return@LaunchedEffect
            }
        }
        val (bmp, _) = ColorExtractor.loadTrackArtwork(context, album.sampleTrack, preferLarge = true)
        if (bmp != null) {
            bitmaps = listOf(bmp.asImageBitmap())
        }
    }

    val fallbackImg = remember(accent) {
        ColorExtractor.buildFallbackCoverBitmap(accent, 256).asImageBitmap()
    }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .border(borderWidth, accent.copy(alpha = 0.65f), shape)
            .background(Color(0xFF0F1315))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val wInt = w.roundToInt().coerceAtLeast(1)
            val hInt = h.roundToInt().coerceAtLeast(1)
            val dividerColor = Color(0xDC0C1012)
            val dividerStroke = 1.5.dp.toPx()

            when (bitmaps.size) {
                0 -> drawCoverCenterCropped(fallbackImg, 0, 0, wInt, hInt)
                1 -> drawCoverCenterCropped(bitmaps[0], 0, 0, wInt, hInt)
                2 -> {
                    val tri1 = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(w, 0f)
                        lineTo(0f, h)
                        close()
                    }
                    clipPath(tri1) {
                        drawCoverCenterCropped(bitmaps[0], 0, 0, wInt, hInt)
                    }
                    val tri2 = Path().apply {
                        moveTo(w, 0f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    clipPath(tri2) {
                        drawCoverCenterCropped(bitmaps[1], 0, 0, wInt, hInt)
                    }
                    drawLine(
                        color = dividerColor,
                        start = Offset(w, 0f),
                        end = Offset(0f, h),
                        strokeWidth = dividerStroke
                    )
                }
                3 -> {
                    val w1 = (w / 3f).roundToInt()
                    val w2 = ((2f * w) / 3f).roundToInt()
                    clipRect(0f, 0f, w1.toFloat(), h) {
                        drawCoverCenterCropped(bitmaps[0], 0, 0, w1, hInt)
                    }
                    clipRect(w1.toFloat(), 0f, w2.toFloat(), h) {
                        drawCoverCenterCropped(bitmaps[1], w1, 0, (w2 - w1).coerceAtLeast(1), hInt)
                    }
                    clipRect(w2.toFloat(), 0f, w, h) {
                        drawCoverCenterCropped(bitmaps[2], w2, 0, (wInt - w2).coerceAtLeast(1), hInt)
                    }
                    drawLine(dividerColor, Offset(w1.toFloat(), 0f), Offset(w1.toFloat(), h), dividerStroke)
                    drawLine(dividerColor, Offset(w2.toFloat(), 0f), Offset(w2.toFloat(), h), dividerStroke)
                }
                else -> {
                    val halfW = (w * 0.5f).roundToInt()
                    val halfH = (h * 0.5f).roundToInt()
                    val rightW = (wInt - halfW).coerceAtLeast(1)
                    val bottomH = (hInt - halfH).coerceAtLeast(1)
                    clipRect(0f, 0f, halfW.toFloat(), halfH.toFloat()) {
                        drawCoverCenterCropped(bitmaps[0], 0, 0, halfW, halfH)
                    }
                    clipRect(halfW.toFloat(), 0f, w, halfH.toFloat()) {
                        drawCoverCenterCropped(bitmaps[1], halfW, 0, rightW, halfH)
                    }
                    clipRect(0f, halfH.toFloat(), halfW.toFloat(), h) {
                        drawCoverCenterCropped(bitmaps[2], 0, halfH, halfW, bottomH)
                    }
                    clipRect(halfW.toFloat(), halfH.toFloat(), w, h) {
                        drawCoverCenterCropped(bitmaps[3], halfW, halfH, rightW, bottomH)
                    }
                    drawLine(dividerColor, Offset(halfW.toFloat(), 0f), Offset(halfW.toFloat(), h), dividerStroke)
                    drawLine(dividerColor, Offset(0f, halfH.toFloat()), Offset(w, halfH.toFloat()), dividerStroke)
                }
            }
        }
    }
}

private fun DrawScope.drawCoverCenterCropped(
    img: ImageBitmap,
    dstX: Int,
    dstY: Int,
    dstW: Int,
    dstH: Int
) {
    if (dstW <= 0 || dstH <= 0) return
    val srcW = img.width.toFloat()
    val srcH = img.height.toFloat()
    val dstAspect = dstW.toFloat() / dstH.toFloat()
    val srcAspect = srcW / srcH
    val cropW: Int
    val cropH: Int
    val cropX: Int
    val cropY: Int
    if (srcAspect > dstAspect) {
        cropH = img.height
        cropW = (img.height * dstAspect).roundToInt().coerceIn(1, img.width)
        cropX = ((img.width - cropW) / 2).coerceAtLeast(0)
        cropY = 0
    } else {
        cropW = img.width
        cropH = (img.width / dstAspect).roundToInt().coerceIn(1, img.height)
        cropX = 0
        cropY = ((img.height - cropH) / 2).coerceAtLeast(0)
    }
    drawImage(
        image = img,
        srcOffset = IntOffset(cropX, cropY),
        srcSize = IntSize(cropW, cropH),
        dstOffset = IntOffset(dstX, dstY),
        dstSize = IntSize(dstW, dstH)
    )
}

@Composable
fun FirstLaunchWelcomeScreen(
    hasPermission: Boolean,
    onSelectMusicFolder: () -> Unit,
    onGrantPermissionAndScan: () -> Unit
) {
    val theme = LocalNanoTheme.current
    val accent = theme.accent
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        NanoGlassCard(
            cornerRadius = 22.dp,
            accentGlow = true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF101416))
                            .border(1.5.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        MarioStarIcon(tint = accent, modifier = Modifier.size(56.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "LIGHTWEIGHT PLAYER • ANDROID",
                            color = theme.accentSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = "NanoTune",
                            color = Color.White,
                            fontFamily = theme.fontPreset.headerFamily,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Native Android Music & Lyrics Player",
                            color = Color(0xFF97A6A4),
                            fontSize = 13.sp
                        )
                    }
                }
                Text(
                    text = "Select a music folder to load your library (.flac, .mp3, .wav, .m4a, .ogg, .opus), or allow audio library access to scan all music on your device. You can add more directories, customize Liquid Glass UI, and tweak Audio FX anytime in Settings.",
                    color = Color(0xFFC4D0CE),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NanoPillButton(
                        text = "Select Music Folder",
                        icon = NanoIconType.ALBUMS,
                        filled = true,
                        accent = accent,
                        onClick = onSelectMusicFolder,
                        modifier = Modifier.fillMaxWidth()
                    )
                    NanoPillButton(
                        text = if (hasPermission) "Scan Device Music Library" else "Allow Audio Access & Scan All",
                        icon = NanoIconType.MUSIC,
                        filled = false,
                        accent = accent,
                        onClick = onGrantPermissionAndScan,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun NanoTopBrandBar(
    subtitle: String,
    accent: Color,
    fxActive: Boolean,
    onOpenFx: () -> Unit
) {
    val theme = LocalNanoTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE0101416))
                    .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                MarioStarIcon(tint = accent, modifier = Modifier.size(28.dp))
            }
            Column {
                Text(
                    text = "NANOTUNE",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.0.sp
                )
                Text(
                    text = subtitle,
                    color = theme.accentSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }
        }
        NanoPillButton(
            text = if (fxActive) "FX • ON" else "FX",
            icon = NanoIconType.FX,
            filled = fxActive,
            accent = accent,
            onClick = onOpenFx
        )
    }
}

@Composable
fun TracksScreen(
    tracks: List<TrackItem>,
    allAlbumsCount: Int,
    activeAlbumGroup: AlbumGroup?,
    activeTrack: TrackItem?,
    activeAlbumFilter: String,
    searchQuery: String,
    activeCover: Bitmap?,
    isScanning: Boolean,
    isPlaying: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onPlayTrack: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onRescan: () -> Unit,
    onClearAlbumFilter: () -> Unit,
    bottomContentPadding: Dp = 172.dp
) {
    val theme = LocalNanoTheme.current
    val accent = theme.accent
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val displayedTracks = remember(tracks, searchQuery) {
        val q = MusicScanner.normalizeSearch(searchQuery)
        if (q.isEmpty()) tracks
        else tracks.filter { tr ->
            MusicScanner.normalizeSearch(tr.title + tr.artist + tr.album).contains(q)
        }
    }
    val headerCover = remember(activeAlbumFilter, tracks, activeCover) {
        if (activeAlbumFilter.isNotEmpty() && tracks.isNotEmpty()) {
            val k = tracks.first().coverHash.ifEmpty { tracks.first().uri.toString() }
            ColorExtractor.getCachedLargeCover(k) ?: activeCover
        } else {
            activeCover
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = if (isLandscape) 24.dp else 16.dp,
            end = if (isLandscape) 24.dp else 16.dp,
            top = 4.dp,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            NanoGlassCard(cornerRadius = 18.dp, accentGlow = true) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (activeAlbumFilter.isNotEmpty() && activeAlbumGroup != null && activeAlbumGroup.coverHashes.size > 1) {
                            AlbumCollageCover(
                                album = activeAlbumGroup,
                                accent = accent,
                                cornerRadius = 12.dp,
                                borderWidth = 2.dp,
                                modifier = Modifier.size(92.dp)
                            )
                        } else {
                            RoundedCoverArt(
                                bitmap = headerCover,
                                accent = accent,
                                cornerRadius = 12.dp,
                                modifier = Modifier.size(92.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val albYear = if (activeAlbumFilter.isNotEmpty()) MusicScanner.resolveAlbumYear(tracks) else ""
                            Text(
                                text = when {
                                    activeAlbumFilter.isNotEmpty() && albYear.isNotEmpty() -> "ALBUM  •  $albYear"
                                    activeAlbumFilter.isNotEmpty() -> "ALBUM"
                                    else -> "LIBRARY  •  ALL TRACKS"
                                },
                                color = theme.accentSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.4.sp
                            )
                            Text(
                                text = activeAlbumFilter.ifEmpty { "All Tracks" },
                                color = Color.White,
                                fontFamily = theme.fontPreset.headerFamily,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subText = if (isScanning) {
                                "Scanning audio library..."
                            } else if (activeAlbumFilter.isNotEmpty()) {
                                "${MusicScanner.resolveAlbumArtist(tracks)}  •  ${tracks.size} tracks"
                            } else {
                                "${tracks.size} tracks  •  $allAlbumsCount albums"
                            }
                            Text(
                                text = subText,
                                color = Color(0xFFC7D4D2),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NanoPillButton(
                            text = "Play",
                            icon = NanoIconType.PLAY,
                            filled = true,
                            accent = accent,
                            onClick = onPlayAll,
                            modifier = Modifier.weight(1f)
                        )
                        NanoPillButton(
                            text = "Shuffle",
                            icon = NanoIconType.SHUFFLE,
                            filled = false,
                            accent = accent,
                            onClick = onShuffleAll,
                            modifier = Modifier.weight(1f)
                        )
                        NanoPillButton(
                            text = if (isScanning) "..." else "Rescan",
                            icon = NanoIconType.REFRESH,
                            filled = false,
                            accent = accent,
                            onClick = onRescan
                        )
                    }
                    if (activeAlbumFilter.isNotEmpty()) {
                        NanoPillButton(
                            text = "Show All Albums",
                            icon = NanoIconType.BACK,
                            filled = false,
                            accent = accent,
                            onClick = onClearAlbumFilter,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    NanoSearchField(
                        query = searchQuery,
                        placeholder = "Search tracks, artists, albums...",
                        accent = accent,
                        onQueryChange = onSearchQueryChange
                    )
                }
            }
        }
        if (displayedTracks.isEmpty()) {
            item {
                NanoGlassCard(cornerRadius = 14.dp) {
                    Text(
                        text = if (tracks.isEmpty()) {
                            "No audio files loaded yet. Tap Rescan or add a folder in Settings."
                        } else {
                            "No tracks match \"$searchQuery\"."
                        },
                        color = Color(0xFF9FB0AE),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        } else {
            itemsIndexed(
                items = displayedTracks,
                key = { _, item -> item.uri.toString() }
            ) { _, track ->
                val isPlayingThis = activeTrack?.uri == track.uri
                val origIndex = tracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
                TrackRowItem(
                    track = track,
                    isCurrent = isPlayingThis,
                    isPlaying = isPlayingThis && isPlaying,
                    accent = accent,
                    accentSecondary = theme.accentSecondary,
                    onClick = { onPlayTrack(origIndex) }
                )
            }
        }
    }
}

@Composable
private fun TrackRowItem(
    track: TrackItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    accentSecondary: Color,
    onClick: () -> Unit
) {
    val rowBg = if (isCurrent) accent.copy(alpha = 0.22f) else Color.Transparent
    val rowBorder = if (isCurrent) accent.copy(alpha = 0.52f) else Color.White.copy(alpha = 0.08f)

    NanoGlassCard(
        cornerRadius = 12.dp,
        accentGlow = isCurrent,
        darkAlpha = if (isCurrent) 0.90f else 0.92f,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(rowBg)
                .border(1.dp, rowBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(30.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent) {
                    val pulseScale = if (isPlaying) {
                        val inf = rememberInfiniteTransition(label = "playPulse")
                        val sc by inf.animateFloat(
                            initialValue = 0.90f,
                            targetValue = 1.18f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(650),
                                repeatMode = AnimRepeatMode.Reverse
                            ),
                            label = "pulseVal"
                        )
                        sc
                    } else 1.0f
                    Text(
                        text = "▶",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.scale(pulseScale)
                    )
                } else {
                    val num = when {
                        track.displayNum in 1..99 -> "%02d".format(track.displayNum)
                        track.displayNum >= 100 -> track.displayNum.toString()
                        else -> "•"
                    }
                    Text(
                        text = num,
                        color = Color(0xFF8DA09D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            TrackThumbnailArt(
                track = track,
                accent = accent,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    color = if (isCurrent) accent else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${track.artist}  •  ${track.album}  •  ${track.formatLabel}",
                    color = Color(0xFF92A5A2),
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = MusicScanner.formatMs(track.durationMs),
                    color = Color(0xFFA3B5B2),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
                if (track.hasLyrics) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(accentSecondary.copy(alpha = 0.16f))
                            .border(1.dp, accentSecondary.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LYRICS",
                            color = accentSecondary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumsScreen(
    albums: List<AlbumGroup>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelectAlbum: (AlbumGroup) -> Unit,
    bottomContentPadding: Dp = 172.dp
) {
    val theme = LocalNanoTheme.current
    val accent = theme.accent
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = if (isLandscape) 4 else 2
    val filtered = remember(albums, searchQuery) {
        val q = MusicScanner.normalizeSearch(searchQuery)
        if (q.isEmpty()) albums
        else albums.filter { MusicScanner.normalizeSearch(it.albumName + it.artistName).contains(q) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (isLandscape) 24.dp else 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Albums",
                color = Color.White,
                fontFamily = theme.fontPreset.headerFamily,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "${filtered.size} albums",
                color = theme.accentSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        NanoSearchField(
            query = searchQuery,
            placeholder = "Search albums or artists...",
            accent = accent,
            onQueryChange = onSearchQueryChange
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filtered, key = { it.albumKey }) { alb ->
                NanoGlassCard(
                    cornerRadius = 14.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectAlbum(alb) }
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AlbumCollageCover(
                            album = alb,
                            accent = accent,
                            borderWidth = 1.5.dp,
                            cornerRadius = 10.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                        Text(
                            text = alb.albumName,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${alb.artistName} • ${alb.trackCount} track${if (alb.trackCount == 1) "" else "s"}",
                            color = Color(0xFF9FB0AE),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NowPlayingAndLyricsScreen(
    track: TrackItem?,
    cover: Bitmap?,
    parsedLyrics: ParsedLyrics,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    fxState: AudioFxState,
    settings: AppSettings,
    isFullSheet: Boolean,
    onDismissSheet: (() -> Unit)? = null,
    onTogglePlayPause: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenFx: () -> Unit,
    onOpenLyricsEditor: () -> Unit,
    bottomContentPadding: Dp = 96.dp
) {
    val theme = LocalNanoTheme.current
    val accent = theme.accent
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var portraitSubTab by remember(isFullSheet) { mutableIntStateOf(if (isFullSheet) 0 else 1) }
    var landscapeShowSplitDetails by remember { mutableStateOf(false) }
    var dragDismissY by remember { mutableFloatStateOf(0f) }

    val containerModifier = if (isFullSheet && onDismissSheet != null) {
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragDismissY.roundToInt().coerceAtLeast(0)) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragDismissY > 170f) onDismissSheet()
                        dragDismissY = 0f
                    },
                    onDragCancel = { dragDismissY = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        dragDismissY = (dragDismissY + dragAmount).coerceAtLeast(0f)
                    }
                )
            }
    } else {
        Modifier.fillMaxSize()
    }

    Column(
        modifier = containerModifier
            .padding(horizontal = 16.dp, vertical = if (isLandscape) 6.dp else 8.dp)
            .padding(bottom = if (isFullSheet) 10.dp else bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 12.dp)
    ) {
        if (isFullSheet && onDismissSheet != null && (!isLandscape || landscapeShowSplitDetails)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                NanoIconButton(
                    icon = NanoIconType.CHEVRON_DOWN,
                    tint = Color.White,
                    onClick = onDismissSheet
                )
                Text(
                    text = "NOW PLAYING",
                    color = theme.accentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.6.sp
                )
                NanoPillButton(
                    text = if (fxState.isAnyFxActive) "FX • ON" else "FX",
                    icon = NanoIconType.FX,
                    filled = fxState.isAnyFxActive,
                    accent = accent,
                    onClick = onOpenFx
                )
            }
        }
        if (!isLandscape) {
            NanoGlassCard(cornerRadius = 14.dp, darkAlpha = 0.90f) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Artwork & Info", "Lyrics").forEachIndexed { idx, label ->
                        val selected = portraitSubTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) accent else Color.Transparent)
                                .clickable { portraitSubTab = idx }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val fg = if (selected) {
                                if (ColorExtractor.luminance(accent) > 0.45f) Color(0xFF07100B) else Color.White
                            } else {
                                Color(0xFFC4D0CE)
                            }
                            Text(
                                text = label,
                                color = fg,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
        if (isLandscape && !landscapeShowSplitDetails) {
            LyricsPanelCard(
                parsedLyrics = parsedLyrics,
                positionMs = positionMs,
                isPlaying = isPlaying,
                playbackSpeed = fxState.speed,
                fontSizeSp = settings.lyricFontSizeSp,
                accent = accent,
                onSeekToMs = onSeekToMs,
                onOpenLyricsEditor = onOpenLyricsEditor,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                extraHeaderAction = if (isFullSheet && onDismissSheet != null) {
                    {
                        NanoIconButton(
                            icon = NanoIconType.CHEVRON_DOWN,
                            tint = Color.White,
                            onClick = onDismissSheet,
                            size = 32.dp,
                            iconSize = 18.dp
                        )
                    }
                } else null
            )
            LandscapeMiniControlStrip(
                track = track,
                cover = cover,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                accent = accent,
                onPrev = onPrev,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNext,
                onToggleSplitDetails = { landscapeShowSplitDetails = true }
            )
        } else if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ArtworkAndDetailsCard(
                    track = track,
                    cover = cover,
                    parsedLyrics = parsedLyrics,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekToMs = onSeekToMs,
                    onNext = onNext,
                    onPrev = onPrev,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onOpenLyricsEditor = onOpenLyricsEditor,
                    onArtworkTap = { landscapeShowSplitDetails = false },
                    modifier = Modifier
                        .weight(0.46f)
                        .fillMaxHeight()
                )
                LyricsPanelCard(
                    parsedLyrics = parsedLyrics,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    playbackSpeed = fxState.speed,
                    fontSizeSp = settings.lyricFontSizeSp,
                    accent = accent,
                    onSeekToMs = onSeekToMs,
                    onOpenLyricsEditor = onOpenLyricsEditor,
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight(),
                    extraHeaderAction = {
                        NanoPillButton(
                            text = "Full Lyrics",
                            icon = NanoIconType.LYRICS,
                            filled = false,
                            accent = accent,
                            onClick = { landscapeShowSplitDetails = false }
                        )
                    }
                )
            }
        } else {
            if (portraitSubTab == 0) {
                ArtworkAndDetailsCard(
                    track = track,
                    cover = cover,
                    parsedLyrics = parsedLyrics,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekToMs = onSeekToMs,
                    onNext = onNext,
                    onPrev = onPrev,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onOpenLyricsEditor = onOpenLyricsEditor,
                    onArtworkTap = { portraitSubTab = 1 },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                LyricsPanelCard(
                    parsedLyrics = parsedLyrics,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    playbackSpeed = fxState.speed,
                    fontSizeSp = settings.lyricFontSizeSp,
                    accent = accent,
                    onSeekToMs = onSeekToMs,
                    onOpenLyricsEditor = onOpenLyricsEditor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                CompactTransportBar(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    fxActive = fxState.isAnyFxActive,
                    accent = accent,
                    onTogglePlayPause = onTogglePlayPause,
                    onPrev = onPrev,
                    onNext = onNext,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onOpenFx = onOpenFx,
                    onSeekToMs = onSeekToMs
                )
            }
        }
    }
}

@Composable
private fun LandscapeMiniControlStrip(
    track: TrackItem?,
    cover: Bitmap?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    accent: Color,
    onPrev: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleSplitDetails: () -> Unit
) {
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    NanoGlassCard(
        cornerRadius = 14.dp,
        accentGlow = true,
        darkAlpha = 0.92f,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RoundedCoverArt(
                    bitmap = cover,
                    accent = accent,
                    cornerRadius = 7.dp,
                    borderWidth = 1.dp,
                    modifier = Modifier.size(34.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds()
                ) {
                    Text(
                        text = track?.title ?: "Nothing Playing",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (track != null) "${track.artist} • ${track.album}" else "Select a track",
                        color = Color(0xFF9EB0AD),
                        fontSize = 10.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.width(152.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    NanoIconButton(
                        icon = NanoIconType.PREV,
                        tint = Color(0xFFD8E4E2),
                        onClick = onPrev,
                        size = 34.dp,
                        iconSize = 18.dp
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(onClick = onTogglePlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        NanoVectorIcon(
                            type = if (isPlaying) NanoIconType.PAUSE else NanoIconType.PLAY,
                            tint = Color(0xFF07120B),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    NanoIconButton(
                        icon = NanoIconType.NEXT,
                        tint = Color(0xFFD8E4E2),
                        onClick = onNext,
                        size = 34.dp,
                        iconSize = 18.dp
                    )
                    NanoIconButton(
                        icon = NanoIconType.INFO,
                        tint = accent,
                        onClick = onToggleSplitDetails,
                        size = 34.dp,
                        iconSize = 18.dp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
    }
}

@Composable
private fun ArtworkAndDetailsCard(
    track: TrackItem?,
    cover: Bitmap?,
    parsedLyrics: ParsedLyrics,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onTogglePlayPause: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenLyricsEditor: () -> Unit,
    onArtworkTap: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val theme = LocalNanoTheme.current
    val accent = theme.accent
    var seeking by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableFloatStateOf(0f) }
    val displayPos = if (seeking) sliderPos.toLong() else positionMs
    val maxDur = durationMs.coerceAtLeast(1L)

    NanoGlassCard(cornerRadius = 18.dp, accentGlow = true, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RoundedCoverArt(
                bitmap = cover,
                accent = accent,
                cornerRadius = 16.dp,
                borderWidth = 2.dp,
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .aspectRatio(1f)
                    .let { m -> if (onArtworkTap != null) m.clickable(onClick = onArtworkTap) else m }
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = track?.title ?: "Nothing Playing",
                    color = Color.White,
                    fontFamily = theme.fontPreset.headerFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (track != null) "${track.artist} • ${track.album}" else "Select a track to play",
                    color = Color(0xFFB4C2C0),
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = MusicScanner.formatMs(displayPos),
                    color = Color(0xFF9EB0AD),
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.widthIn(min = 44.dp)
                )
                Slider(
                    value = (displayPos.toFloat() / maxDur.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { frac ->
                        seeking = true
                        sliderPos = frac * maxDur.toFloat()
                    },
                    onValueChangeFinished = {
                        seeking = false
                        onSeekToMs(sliderPos.toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = MusicScanner.formatMs(durationMs),
                    color = Color(0xFF9EB0AD),
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 44.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NanoIconButton(
                    icon = NanoIconType.SHUFFLE,
                    tint = if (shuffleEnabled) accent else Color(0xFFB4C2C0),
                    onClick = onToggleShuffle
                )
                NanoIconButton(
                    icon = NanoIconType.PREV,
                    tint = Color(0xFFD8E4E2),
                    onClick = onPrev,
                    size = 44.dp,
                    iconSize = 22.dp
                )
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    NanoVectorIcon(
                        type = if (isPlaying) NanoIconType.PAUSE else NanoIconType.PLAY,
                        tint = Color(0xFF07120B),
                        modifier = Modifier.size(24.dp)
                    )
                }
                NanoIconButton(
                    icon = NanoIconType.NEXT,
                    tint = Color(0xFFD8E4E2),
                    onClick = onNext,
                    size = 44.dp,
                    iconSize = 22.dp
                )
                val repIcon = if (repeatMode == RepeatMode.ONE) NanoIconType.REPEAT_ONE else NanoIconType.REPEAT
                val repTint = if (repeatMode == RepeatMode.OFF) Color(0xFF6D7E7C) else accent
                NanoIconButton(
                    icon = repIcon,
                    tint = repTint,
                    onClick = onCycleRepeat
                )
            }
            if (track != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xC00A0E0F))
                        .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    val sr = if (track.sampleRate > 0) "${"%.1f".format(track.sampleRate / 1000f)} kHz" else "—"
                    val bps = if (track.bitsPerSample > 0) "${track.bitsPerSample}-bit" else "Lossy"
                    val ch = if (track.channels == 2) "Stereo" else "${track.channels}ch"
                    val br = if (track.bitrate > 0) "${track.bitrate / 1000} kbps" else "Variable"
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        DetailRow("Format", track.codecDetail)
                        DetailRow("Audio", "$sr • $bps • $ch")
                        DetailRow("Bitrate", br)
                        DetailRow("Size", MusicScanner.formatBytes(track.fileSize))
                        DetailRow("Album", track.album)
                        DetailRow("Lyrics", parsedLyrics.sourceLabel)
                    }
                }
            }
            NanoPillButton(
                text = "Edit Lyrics",
                icon = NanoIconType.EDIT,
                filled = false,
                accent = accent,
                onClick = onOpenLyricsEditor,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF849794), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = Color(0xFFE5EFED),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LyricsPanelCard(
    parsedLyrics: ParsedLyrics,
    positionMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    fontSizeSp: Float,
    accent: Color,
    onSeekToMs: (Long) -> Unit,
    onOpenLyricsEditor: () -> Unit,
    modifier: Modifier = Modifier,
    extraHeaderAction: (@Composable () -> Unit)? = null
) {
    val theme = LocalNanoTheme.current
    NanoGlassCard(cornerRadius = 18.dp, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when {
                        parsedLyrics.hasElrcWords -> "LYRICS • WORD SYNC (.ELRC)"
                        parsedLyrics.isSynced -> "LYRICS • SYNCED"
                        else -> "LYRICS"
                    },
                    color = theme.accentSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.4.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    extraHeaderAction?.invoke()
                    NanoPillButton(
                        text = "Edit Lyrics",
                        icon = NanoIconType.EDIT,
                        filled = false,
                        accent = accent,
                        onClick = onOpenLyricsEditor
                    )
                }
            }
            AnimatedLyricsCanvas(
                parsedLyrics = parsedLyrics,
                positionMs = positionMs,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
                fontSizeSp = fontSizeSp,
                fontFamily = theme.fontPreset.headerFamily,
                accentColor = accent,
                onSeekToMs = onSeekToMs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun CompactTransportBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    fxActive: Boolean,
    accent: Color,
    onTogglePlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenFx: () -> Unit,
    onSeekToMs: (Long) -> Unit
) {
    val maxDur = durationMs.coerceAtLeast(1L)
    var seeking by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableFloatStateOf(0f) }
    val displayPos = if (seeking) sliderPos.toLong() else positionMs

    NanoGlassCard(cornerRadius = 16.dp, darkAlpha = 0.92f) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = MusicScanner.formatMs(displayPos),
                    color = Color(0xFF9EB0AD),
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.widthIn(min = 44.dp)
                )
                Slider(
                    value = (displayPos.toFloat() / maxDur.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { frac ->
                        seeking = true
                        sliderPos = frac * maxDur.toFloat()
                    },
                    onValueChangeFinished = {
                        seeking = false
                        onSeekToMs(sliderPos.toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = MusicScanner.formatMs(durationMs),
                    color = Color(0xFF9EB0AD),
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 44.dp)
                )
            }
                        Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                NanoIconButton(
                    icon = NanoIconType.SHUFFLE,
                    tint = if (shuffleEnabled) accent else Color(0xFF9EB0AD),
                    onClick = onToggleShuffle,
                    size = 34.dp,
                    iconSize = 17.dp,
                    modifier = Modifier.align(Alignment.CenterStart)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    NanoIconButton(icon = NanoIconType.PREV, tint = Color.White, onClick = onPrev)
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(onClick = onTogglePlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        NanoVectorIcon(
                            type = if (isPlaying) NanoIconType.PAUSE else NanoIconType.PLAY,
                            tint = Color(0xFF07120B),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    NanoIconButton(icon = NanoIconType.NEXT, tint = Color.White, onClick = onNext)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    val repIcon = if (repeatMode == RepeatMode.ONE) NanoIconType.REPEAT_ONE else NanoIconType.REPEAT
                    val repTint = if (repeatMode == RepeatMode.OFF) Color(0xFF6D7E7C) else accent
                    NanoIconButton(
                        icon = repIcon,
                        tint = repTint,
                        onClick = onCycleRepeat,
                        size = 34.dp,
                        iconSize = 17.dp
                    )
                    NanoIconButton(
                        icon = NanoIconType.FX,
                        tint = if (fxActive) accent else Color(0xFFD8E4E2),
                        onClick = onOpenFx,
                        size = 34.dp,
                        iconSize = 17.dp
                    )
                }
            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioFxBottomSheet(
    fxState: AudioFxState,
    accent: Color,
    onDismiss: () -> Unit
) {
    val theme = LocalNanoTheme.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20A0E10),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "AUDIO FX  •  SLOWED + REVERB / SPEED / PITCH / BASS / LO-FI",
                color = theme.accentSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.4.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NanoPillButton(
                        text = FxPreset.NORMAL.label,
                        filled = false,
                        accent = accent,
                        onClick = { AudioFxStore.applyPreset(FxPreset.NORMAL) },
                        modifier = Modifier.weight(1f)
                    )
                    NanoPillButton(
                        text = FxPreset.LOFI_MUFFLE.label,
                        filled = fxState.lofiEnabled,
                        accent = accent,
                        onClick = { AudioFxStore.applyPreset(FxPreset.LOFI_MUFFLE) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NanoPillButton(
                        text = FxPreset.SLOWED_REVERB.label,
                        filled = false,
                        accent = accent,
                        onClick = { AudioFxStore.applyPreset(FxPreset.SLOWED_REVERB) },
                        modifier = Modifier.weight(1.3f)
                    )
                    NanoPillButton(
                        text = FxPreset.WARM_BASS.label,
                        filled = false,
                        accent = accent,
                        onClick = { AudioFxStore.applyPreset(FxPreset.WARM_BASS) },
                        modifier = Modifier.weight(1f)
                    )
                    NanoPillButton(
                        text = FxPreset.SPED_UP.label,
                        filled = false,
                        accent = accent,
                        onClick = { AudioFxStore.applyPreset(FxPreset.SPED_UP) },
                        modifier = Modifier.weight(0.9f)
                    )
                }
            }
            FxSliderRow(
                label = "Speed",
                valueText = "%.2fx".format(fxState.speed),
                value = fxState.speed,
                range = 0.60f..1.40f,
                accent = accent,
                enabled = true,
                onValueChange = { v -> AudioFxStore.update { it.copy(speed = v) } }
            )
            FxSliderRow(
                label = "Pitch",
                valueText = "%+.1f st".format(fxState.pitchSemitones),
                value = fxState.pitchSemitones,
                range = -6.0f..6.0f,
                accent = accent,
                enabled = !fxState.linkPitchToSpeed,
                onValueChange = { v -> AudioFxStore.update { it.copy(pitchSemitones = v) } }
            )
            FxSliderRow(
                label = "Bass Boost",
                valueText = "${(fxState.bassBoost * 100).roundToInt()}%",
                value = fxState.bassBoost,
                range = 0f..1f,
                accent = accent,
                enabled = true,
                onValueChange = { v -> AudioFxStore.update { it.copy(bassBoost = v) } }
            )
            FxSliderRow(
                label = "Hall Reverb",
                valueText = "${(fxState.reverbMix * 100).roundToInt()}%",
                value = fxState.reverbMix,
                range = 0f..1f,
                accent = accent,
                enabled = true,
                onValueChange = { v -> AudioFxStore.update { it.copy(reverbMix = v) } }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        AudioFxStore.update { it.copy(linkPitchToSpeed = !it.linkPitchToSpeed) }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Link pitch to playback speed (natural vinyl tone)",
                    color = Color(0xFFDCE6E4),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                )
                Switch(
                    checked = fxState.linkPitchToSpeed,
                    onCheckedChange = { checked ->
                        AudioFxStore.update { it.copy(linkPitchToSpeed = checked) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent
                    )
                )
            }
        }
    }
}

@Composable
private fun FxSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = if (enabled) Color(0xFFC7D4D2) else Color(0xFF6E7B79),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = valueText,
                color = if (enabled) Color.White else Color(0xFF6E7B79),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.16f),
                disabledThumbColor = Color(0xFF7A8486),
                disabledActiveTrackColor = Color(0xFF4A5456),
                disabledInactiveTrackColor = Color(0xFF2D3436)
            )
        )
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    accent: Color,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRescanLibrary: () -> Unit,
    bottomContentPadding: Dp = 172.dp
) {
    val theme = LocalNanoTheme.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fontDropdownOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isLandscape) 24.dp else 16.dp)
            .padding(bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Settings",
            color = Color.White,
            fontFamily = theme.fontPreset.headerFamily,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(top = 6.dp)
        )
        val directoriesCard: @Composable () -> Unit = {
            NanoGlassCard(cornerRadius = 16.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Music Directories",
                            color = Color(0xFFEAF0EE),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        NanoPillButton(
                            text = "+ Add Folder",
                            filled = true,
                            accent = accent,
                            onClick = onAddFolder
                        )
                    }
                    if (settings.musicFolders.isEmpty()) {
                        Text(
                            text = "Scanning entire device MediaStore audio library. Add folders above to scan chosen directories instead.",
                            color = Color(0xFF9FB0AE),
                            fontSize = 12.sp
                        )
                    } else {
                        settings.musicFolders.forEach { folderUri ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xB80A0E0F))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = Uri.decode(folderUri).substringAfterLast(':'),
                                    color = Color(0xFFEAF0EE),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                NanoIconButton(
                                    icon = NanoIconType.CLOSE,
                                    tint = Color(0xFF9EB0AD),
                                    onClick = { onRemoveFolder(folderUri) },
                                    size = 28.dp,
                                    iconSize = 14.dp
                                )
                            }
                        }
                    }
                    SettingsToggleRow(
                        title = "Scan subfolders inside music directories",
                        checked = settings.recursiveScan,
                        accent = accent,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(recursiveScan = v) } }
                    )
                    NanoPillButton(
                        text = "Rescan Library Now",
                        icon = NanoIconType.REFRESH,
                        filled = false,
                        accent = accent,
                        onClick = onRescanLibrary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        val appearanceCard: @Composable () -> Unit = {
            NanoGlassCard(cornerRadius = 16.dp, accentGlow = settings.liquidGlassMode) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "APPEARANCE & LIQUID GLASS",
                        color = theme.accentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.4.sp
                    )
                    SettingsToggleRow(
                        title = "Liquid Glass UI Mode (frosted backdrop refraction & specular rim highlights)",
                        checked = settings.liquidGlassMode,
                        accent = accent,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(liquidGlassMode = v) } }
                    )
                    if (settings.liquidGlassMode) {
                        SettingsSliderRow(
                            label = "Glass Blur & Refraction Intensity",
                            valueText = "${(settings.liquidGlassIntensity * 100).roundToInt()}%",
                            value = settings.liquidGlassIntensity,
                            range = 0.15f..1.0f,
                            accent = accent,
                            onValueChange = { v -> onUpdateSettings { it.copy(liquidGlassIntensity = v) } }
                        )
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                    SettingsToggleRow(
                        title = "Adaptive UI & Star Colors (auto-theme from album covers, B&W → silver-grey)",
                        checked = settings.adaptiveColors,
                        accent = accent,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(adaptiveColors = v) } }
                    )
                    SettingsToggleRow(
                        title = "Always keep blurred album cover background active",
                        checked = settings.alwaysBlurBg,
                        accent = accent,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(alwaysBlurBg = v) } }
                    )
                    SettingsSliderRow(
                        label = "Background Blur Intensity",
                        valueText = "${settings.blurRadiusDp.roundToInt()} dp",
                        value = settings.blurRadiusDp,
                        range = 10f..66f,
                        accent = accent,
                        onValueChange = { v -> onUpdateSettings { it.copy(blurRadiusDp = v) } }
                    )
                }
            }
        }
        val typographyCard: @Composable () -> Unit = {
            NanoGlassCard(cornerRadius = 16.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "TYPOGRAPHY & LYRICS",
                        color = theme.accentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.4.sp
                    )
                    Box {
                        val activePreset = NanoFontPresets[settings.fontPresetIndex.coerceIn(0, NanoFontPresets.lastIndex)]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xC80C1113))
                                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                                .clickable { fontDropdownOpen = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("App & Lyrics Font", color = Color(0xFF97A6A4), fontSize = 11.sp)
                                Text(
                                    text = activePreset.name,
                                    color = Color.White,
                                    fontFamily = activePreset.headerFamily,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            NanoVectorIcon(
                                type = NanoIconType.CHEVRON_DOWN,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = fontDropdownOpen,
                            onDismissRequest = { fontDropdownOpen = false },
                            modifier = Modifier.background(Color(0xFF12181A))
                        ) {
                            NanoFontPresets.forEachIndexed { idx, preset ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = preset.name,
                                            color = if (idx == settings.fontPresetIndex) accent else Color(0xFFEAF0EE),
                                            fontFamily = preset.headerFamily,
                                            fontWeight = if (idx == settings.fontPresetIndex) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        fontDropdownOpen = false
                                        onUpdateSettings { it.copy(fontPresetIndex = idx) }
                                    }
                                )
                            }
                        }
                    }
                    SettingsSliderRow(
                        label = "Lyrics Font Size",
                        valueText = "${settings.lyricFontSizeSp.roundToInt()} sp",
                        value = settings.lyricFontSizeSp,
                        range = 14f..28f,
                        accent = accent,
                        onValueChange = { v -> onUpdateSettings { it.copy(lyricFontSizeSp = v) } }
                    )
                }
            }
        }
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    directoriesCard()
                    typographyCard()
                }
                Box(modifier = Modifier.weight(1f)) {
                    appearanceCard()
                }
            }
        } else {
            directoriesCard()
            appearanceCard()
            typographyCard()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = Color(0xFFDCE6E4),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent
            )
        )
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color(0xFFD2DFDD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(valueText, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f)
            )
        )
    }
}

@Composable
fun AboutScreen(
    accent: Color,
    bottomContentPadding: Dp = 172.dp
) {
    val theme = LocalNanoTheme.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val highContrastSubtitle = remember(accent) {
        if (ColorExtractor.luminance(accent) < 0.48f) {
            lerp(accent, Color.White, 0.58f)
        } else {
            accent
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isLandscape) 24.dp else 16.dp)
            .padding(bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        NanoGlassCard(cornerRadius = 18.dp, accentGlow = true) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF101416))
                            .border(2.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        MarioStarIcon(tint = accent, modifier = Modifier.size(64.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LIGHTWEIGHT • NATIVE ANDROID PLAYER",
                            color = theme.accentSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            text = "NanoTune",
                            color = Color.White,
                            fontFamily = theme.fontPreset.headerFamily,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Fast, lightweight lossless music player for Android (Media3 + PCM DSP)",
                            color = highContrastSubtitle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "NanoTune is a clean, lightweight local music player built for Android 8+. " +
                        "Drop in multiple music folders, browse your library by Tracks or Albums, read high-FPS " +
                        "animated .ELRC / .LRC and embedded FLAC/ID3/MP4 lyrics, shape your sound with deep 155 Hz " +
                        "Bass Boost, Speed, Pitch, Hall Reverb, and Lo-Fi Muffle FX, and watch the UI & Star icon " +
                        "adapt dynamically to every album cover.\n\n" +
                        "Created by @mirakwgnr.",
                    color = Color(0xFFD9E4E2),
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xD00B0F10))
                        .border(1.dp, Color.White.copy(alpha = 0.11f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = "• Audio Formats: FLAC (16/24-bit), WAV, MP3, M4A, OGG, OPUS\n" +
                            "• Audio FX Rack: Speed (0.60x–1.40x), Pitch (±6 st), 155 Hz Sub-Bass Boost, Hall Reverb, 1400 Hz Lo-Fi Muffle\n" +
                            "• Lyrics Engine: 60–120+ Hz Word-by-Word .ELRC + .LRC + Embedded Tags + Live Sync Editor\n" +
                            "• Appearance: Adaptive HSV Album Theming, B&W Silver Fallback & Liquid Glass UI Mode",
                        color = Color(0xFFB4C6C3),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MiniPlayerAndBottomNav(
    selectedTab: NavTab,
    showMiniPlayer: Boolean,
    track: TrackItem?,
    cover: Bitmap?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPrev: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpandMiniPlayer: () -> Unit,
    onSelectTab: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalNanoTheme.current
    val accent = theme.accent
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val onAccent = if (ColorExtractor.luminance(accent) > 0.45f) Color(0xFF07100B) else Color.White
    var swipeY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showMiniPlayer) {
            NanoGlassCard(
                cornerRadius = 16.dp,
                accentGlow = true,
                darkAlpha = 0.92f,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (swipeY < -40f) onExpandMiniPlayer()
                                swipeY = 0f
                            },
                            onDragCancel = { swipeY = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                swipeY = (swipeY + dragAmount).coerceAtMost(0f)
                            }
                        )
                    }
                    .clickable(onClick = onExpandMiniPlayer)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RoundedCoverArt(
                            bitmap = cover,
                            accent = accent,
                            cornerRadius = 8.dp,
                            borderWidth = 1.5.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clipToBounds()
                        ) {
                            Text(
                                text = track?.title ?: "No Track Selected",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (track != null) "${track.artist} • ${track.album}" else "Tap a track to play",
                                color = Color(0xFF9EB0AD),
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(
                            modifier = Modifier.width(114.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            NanoIconButton(
                                icon = NanoIconType.PREV,
                                tint = Color(0xFFD8E4E2),
                                onClick = onPrev,
                                size = 34.dp,
                                iconSize = 18.dp
                            )
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                                    .clickable(onClick = onTogglePlayPause),
                                contentAlignment = Alignment.Center
                            ) {
                                NanoVectorIcon(
                                    type = if (isPlaying) NanoIconType.PAUSE else NanoIconType.PLAY,
                                    tint = onAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            NanoIconButton(
                                icon = NanoIconType.NEXT,
                                tint = Color(0xFFD8E4E2),
                                onClick = onNext,
                                size = 34.dp,
                                iconSize = 18.dp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.22f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(accent)
                        )
                    }
                }
            }
        }
        NanoGlassCard(
            cornerRadius = 20.dp,
            darkAlpha = 0.92f,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    val itemColor by animateColorAsState(
                        targetValue = if (selected) accent else Color(0xFFB4C2C0),
                        label = "navTint"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { onSelectTab(tab) }
                            .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        NanoVectorIcon(
                            type = tab.icon,
                            tint = itemColor,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = tab.label,
                            color = itemColor,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
