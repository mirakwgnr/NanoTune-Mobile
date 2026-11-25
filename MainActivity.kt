package com.nanotune.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )

        setContent {
            NanoTuneRoot(viewModel = viewModel)
        }
    }
}

@Composable
private fun NanoTuneRoot(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val displayedTracks by viewModel.displayedTracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val activeAlbumFilter by viewModel.activeAlbumFilter.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    val activeTrack by viewModel.activeTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()

    val activeCover by viewModel.activeCover.collectAsStateWithLifecycle()
    val processedBackdrop by viewModel.processedBackdrop.collectAsStateWithLifecycle()
    val themeColors by viewModel.themeColors.collectAsStateWithLifecycle()
    val parsedLyrics by viewModel.parsedLyrics.collectAsStateWithLifecycle()
    val fxState by viewModel.fxState.collectAsStateWithLifecycle()

    val audioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            viewModel.completeOnboarding()
        } else {
            Toast.makeText(
                context,
                "Audio permission denied — select a music folder manually to load tracks.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            viewModel.addMusicFolder(uri.toString())
        }
    }

    var didInitialDeltaSync by remember { mutableStateOf(false) }

    LaunchedEffect(hasAudioPermission, settings.hasCompletedOnboarding, settings.musicFolders) {
        if (settings.hasCompletedOnboarding && (hasAudioPermission || settings.musicFolders.isNotEmpty())) {
            if (!didInitialDeltaSync) {
                didInitialDeltaSync = true
                viewModel.scanLibrary(forceFullRescan = false)
            }
        }
    }

    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedTab by remember { mutableStateOf(NavTab.TRACKS) }
    var trackSearchQuery by remember { mutableStateOf("") }
    var albumSearchQuery by remember { mutableStateOf("") }
    var showFullNowPlaying by remember { mutableStateOf(false) }
    var showFxSheet by remember { mutableStateOf(false) }
    var showLyricsEditor by remember { mutableStateOf(false) }

    BackHandler(enabled = showLyricsEditor || showFullNowPlaying || selectedTab != NavTab.TRACKS) {
        when {
            showLyricsEditor -> showLyricsEditor = false
            showFullNowPlaying -> showFullNowPlaying = false
            selectedTab != NavTab.TRACKS -> selectedTab = NavTab.TRACKS
        }
    }

    NanoTuneTheme(
        rawThemeColors = themeColors,
        settings = settings,
        backdropBitmap = processedBackdrop,
        rootSize = rootSize
    ) {
        val theme = LocalNanoTheme.current
        val showBlurredBg = isPlaying ||
            activeTrack != null ||
            selectedTab == NavTab.LYRICS ||
            showFullNowPlaying ||
            showLyricsEditor ||
            settings.alwaysBlurBg

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { rootSize = it }
        ) {
            BlurredAlbumBackdrop(
                processedBackdrop = processedBackdrop,
                showBlurred = showBlurredBg,
                accent = theme.accent,
                blurRadiusDp = settings.blurRadiusDp
            )

            if (!settings.hasCompletedOnboarding && !hasAudioPermission && settings.musicFolders.isEmpty()) {
                FirstLaunchWelcomeScreen(
                    hasPermission = hasAudioPermission,
                    onSelectMusicFolder = {
                        folderPickerLauncher.launch(null)
                    },
                    onGrantPermissionAndScan = {
                        if (hasAudioPermission) {
                            viewModel.completeOnboarding()
                        } else {
                            permissionLauncher.launch(audioPermission)
                        }
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        NanoTopBrandBar(
                            subtitle = when (selectedTab) {
                                NavTab.TRACKS -> "${allTracks.size} TRACKS"
                                NavTab.ALBUMS -> "${albums.size} ALBUMS"
                                NavTab.LYRICS -> activeTrack?.album?.uppercase() ?: "LYRICS"
                                NavTab.SETTINGS -> if (settings.liquidGlassMode) "LIQUID GLASS" else "PREFERENCES"
                                NavTab.ABOUT -> "MUSIC PLAYER"
                            },
                            accent = theme.accent,
                            fxActive = fxState.isAnyFxActive,
                            onOpenFx = { showFxSheet = true }
                        )

                        val showMini = selectedTab != NavTab.LYRICS
                        val bottomPad = if (showMini) 172.dp else 96.dp

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            when (selectedTab) {
                                NavTab.TRACKS -> {
                                    TracksScreen(
                                        tracks = displayedTracks,
                                        allAlbumsCount = albums.size,
                                        activeAlbumGroup = albums.firstOrNull { it.albumName == activeAlbumFilter },
                                        activeTrack = activeTrack,
                                        activeAlbumFilter = activeAlbumFilter,
                                        searchQuery = trackSearchQuery,
                                        activeCover = activeCover,
                                        isScanning = isScanning,
                                        isPlaying = isPlaying,
                                        onSearchQueryChange = { trackSearchQuery = it },
                                        onPlayTrack = { idx -> viewModel.playFromList(idx) },
                                        onPlayAll = { viewModel.playAllFromStart() },
                                        onShuffleAll = { viewModel.playShuffled() },
                                        onRescan = {
                                            if (hasAudioPermission || settings.musicFolders.isNotEmpty()) {
                                                viewModel.scanLibrary(forceFullRescan = true)
                                            } else {
                                                permissionLauncher.launch(audioPermission)
                                            }
                                        },
                                        onClearAlbumFilter = { viewModel.clearAlbumFilter() },
                                        bottomContentPadding = bottomPad
                                    )
                                }

                                NavTab.ALBUMS -> {
                                    AlbumsScreen(
                                        albums = albums,
                                        searchQuery = albumSearchQuery,
                                        onSearchQueryChange = { albumSearchQuery = it },
                                        onSelectAlbum = { alb ->
                                            viewModel.selectAlbumFilter(alb.albumName)
                                            selectedTab = NavTab.TRACKS
                                        },
                                        bottomContentPadding = bottomPad
                                    )
                                }

                                NavTab.LYRICS -> {
                                    NowPlayingAndLyricsScreen(
                                        track = activeTrack,
                                        cover = activeCover,
                                        parsedLyrics = parsedLyrics,
                                        isPlaying = isPlaying,
                                        positionMs = positionMs,
                                        durationMs = durationMs,
                                        shuffleEnabled = shuffleEnabled,
                                        repeatMode = repeatMode,
                                        fxState = fxState,
                                        settings = settings,
                                        isFullSheet = false,
                                        onTogglePlayPause = { viewModel.togglePlayPause() },
                                        onSeekToMs = { viewModel.seekToMs(it) },
                                        onNext = { viewModel.skipNext() },
                                        onPrev = { viewModel.skipPrevious() },
                                        onToggleShuffle = { viewModel.toggleShuffle() },
                                        onCycleRepeat = { viewModel.cycleRepeatMode() },
                                        onOpenFx = { showFxSheet = true },
                                        onOpenLyricsEditor = {
                                            if (activeTrack == null) {
                                                Toast.makeText(context, "Play or select a track first.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                showLyricsEditor = true
                                            }
                                        },
                                        bottomContentPadding = bottomPad
                                    )
                                }

                                NavTab.SETTINGS -> {
                                    SettingsScreen(
                                        settings = settings,
                                        accent = theme.accent,
                                        onAddFolder = { folderPickerLauncher.launch(null) },
                                        onRemoveFolder = { viewModel.removeMusicFolder(it) },
                                        onUpdateSettings = { viewModel.updateSettings(it) },
                                        onRescanLibrary = { viewModel.scanLibrary(forceFullRescan = true) },
                                        bottomContentPadding = bottomPad
                                    )
                                }

                                NavTab.ABOUT -> {
                                    AboutScreen(
                                        accent = theme.accent,
                                        bottomContentPadding = bottomPad
                                    )
                                }
                            }
                        }
                    }

                    MiniPlayerAndBottomNav(
                        selectedTab = selectedTab,
                        showMiniPlayer = selectedTab != NavTab.LYRICS,
                        track = activeTrack,
                        cover = activeCover,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onPrev = { viewModel.skipPrevious() },
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onNext = { viewModel.skipNext() },
                        onExpandMiniPlayer = { showFullNowPlaying = true },
                        onSelectTab = { selectedTab = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                AnimatedVisibility(
                    visible = showFullNowPlaying,
                    enter = slideInVertically(
                        animationSpec = spring<IntOffset>(dampingRatio = 0.82f, stiffness = 380f),
                        initialOffsetY = { it }
                    ) + fadeIn(tween(220)),
                    exit = slideOutVertically(
                        animationSpec = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 400f),
                        targetOffsetY = { it }
                    ) + fadeOut(tween(180))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        BlurredAlbumBackdrop(
                            processedBackdrop = processedBackdrop,
                            showBlurred = true,
                            accent = theme.accent,
                            blurRadiusDp = settings.blurRadiusDp
                        )
                        NowPlayingAndLyricsScreen(
                            track = activeTrack,
                            cover = activeCover,
                            parsedLyrics = parsedLyrics,
                            isPlaying = isPlaying,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            shuffleEnabled = shuffleEnabled,
                            repeatMode = repeatMode,
                            fxState = fxState,
                            settings = settings,
                            isFullSheet = true,
                            onDismissSheet = { showFullNowPlaying = false },
                            onTogglePlayPause = { viewModel.togglePlayPause() },
                            onSeekToMs = { viewModel.seekToMs(it) },
                            onNext = { viewModel.skipNext() },
                            onPrev = { viewModel.skipPrevious() },
                            onToggleShuffle = { viewModel.toggleShuffle() },
                            onCycleRepeat = { viewModel.cycleRepeatMode() },
                            onOpenFx = { showFxSheet = true },
                            onOpenLyricsEditor = {
                                if (activeTrack == null) {
                                    Toast.makeText(context, "Play or select a track first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    showLyricsEditor = true
                                }
                            }
                        )
                    }
                }

                if (showFxSheet) {
                    AudioFxBottomSheet(
                        fxState = fxState,
                        accent = theme.accent,
                        onDismiss = { showFxSheet = false }
                    )
                }

                if (showLyricsEditor) {
                    LyricsEditorModal(
                        initialRawText = parsedLyrics.rawText,
                        currentPositionMs = positionMs,
                        accent = theme.accent,
                        onDismiss = { showLyricsEditor = false },
                        onSave = { rawText ->
                            viewModel.saveEditedLyrics(rawText) { res ->
                                res.onSuccess { dest ->
                                    showLyricsEditor = false
                                    Toast.makeText(context, "Saved lyrics to $dest", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    Toast.makeText(context, "Failed to save: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}