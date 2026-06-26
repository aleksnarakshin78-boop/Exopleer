package com.example.exopleer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

data class AudioTrack(val uri: Uri, val title: String, val durationMs: Long, val durationStr: String)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val playerState = mutableStateOf<Player?>(null)
    private val tracksState = mutableStateOf<List<AudioTrack>>(emptyList())
    private val audioSessionIdState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, MediaService::class.java)
        startService(serviceIntent)

        val sessionToken = SessionToken(this, ComponentName(this, MediaService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val controller = controllerFuture?.get()
            playerState.value = controller
            
            controller?.let {
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                val id = it.sessionExtras.getInt("audio_session_id", 0)
                if (id != 0) audioSessionIdState.intValue = id
            }

            controller?.addListener(object : Player.Listener {
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId != 0 && audioSessionIdState.intValue != audioSessionId) {
                        audioSessionIdState.intValue = audioSessionId
                    }
                }

                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onEvents(player: Player, events: Player.Events) {
                    val id = controller?.sessionExtras?.getInt("audio_session_id", 0) ?: 0
                    if (id != 0 && audioSessionIdState.intValue != id) {
                        audioSessionIdState.intValue = id
                    }
                }
            })
        }, MoreExecutors.directExecutor())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    var hasPermission by remember { mutableStateOf(checkAudioPermission()) }
                    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> hasPermission = isGranted }

                    if (hasPermission) {
                        LaunchedEffect(Unit) { tracksState.value = loadAudioFiles() }
                        PlayerScreen(player = playerState.value, tracks = tracksState.value, audioSessionId = audioSessionIdState.intValue)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Button(onClick = {
                                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                                permissionLauncher.launch(permission)
                            }) { Text("Разрешить доступ к музыке") }
                        }
                    }
                }
            }
        }
    }

    private fun checkAudioPermission() = ContextCompat.checkSelfPermission(this, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private suspend fun loadAudioFiles(): List<AudioTrack> = withContext(Dispatchers.IO) {
        val trackList = mutableListOf<AudioTrack>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Без названия"
                val durationMs = cursor.getLong(durCol)
                val min = (durationMs / 1000) / 60
                val sec = (durationMs / 1000) % 60
                val durationStr = String.format(Locale.getDefault(), "%02d:%02d", min, sec)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                trackList.add(AudioTrack(uri, title, durationMs, durationStr))
            }
        }
        trackList
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

@Composable
fun PlayerScreen(player: Player?, tracks: List<AudioTrack>, audioSessionId: Int) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("audio_settings", Context.MODE_PRIVATE) }
    
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var isShuffleOn by remember { mutableStateOf(false) }
    var isLoopOn by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }

    var showPlaylist by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var albumArt by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    var countdown by remember { mutableIntStateOf(0) }
    
    // Таймер сна (в секундах)
    var sleepTimerSeconds by remember { mutableLongStateOf(0L) }

    // Логика таймера сна
    LaunchedEffect(sleepTimerSeconds, isPlaying) {
        if (sleepTimerSeconds > 0 && isPlaying) {
            while (sleepTimerSeconds > 0) {
                delay(1000L)
                sleepTimerSeconds--
            }
            player?.pause()
        }
    }

    // Эквалайзер и Басс Буст
    val equalizer = remember(audioSessionId) {
        if (audioSessionId != 0) {
            try {
                Equalizer(0, audioSessionId).apply {
                    enabled = true
                    for (i in 0 until numberOfBands) {
                        val level = prefs.getInt("eq_band_$i", 0).toShort()
                        setBandLevel(i.toShort(), level)
                    }
                }
            } catch (e: Exception) { null }
        } else null
    }

    val bassBoost = remember(audioSessionId) {
        if (audioSessionId != 0) {
            try {
                BassBoost(0, audioSessionId).apply {
                    enabled = true
                    val strength = prefs.getInt("bass_strength", 0).toShort()
                    setStrength(strength)
                }
            } catch (e: Exception) { null }
        } else null
    }

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val sheetHeight = screenHeight * 0.8f // Чуть увеличим шторку для таймера сна

    val playlistOffset by animateDpAsState(targetValue = if (showPlaylist) 0.dp else sheetHeight, animationSpec = tween(durationMillis = 600), label = "PlaylistAnimation")
    val eqOffset by animateDpAsState(targetValue = if (showEq) 0.dp else sheetHeight, animationSpec = tween(durationMillis = 600), label = "EqAnimation")
    val scrimAlpha by animateFloatAsState(targetValue = if (showPlaylist || showEq) 0.6f else 0f, animationSpec = tween(durationMillis = 600), label = "ScrimAnimation")

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    LaunchedEffect(tracks, player) {
        if (player != null && tracks.isNotEmpty() && player.mediaItemCount == 0) {
            player.setMediaItems(tracks.map { MediaItem.fromUri(it.uri) })
            player.prepare()
        }
    }

    LaunchedEffect(currentIndex, tracks) {
        if (currentIndex in tracks.indices) {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, tracks[currentIndex].uri)
                    val artBytes = retriever.embeddedPicture
                    albumArt = if (artBytes != null) BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) else null
                } catch (e: Exception) { albumArt = null } finally { try { retriever.release() } catch (e: Exception) {} }
            }
        } else albumArt = null
    }

    val targetBackgroundColor = remember(albumArt, currentIndex) {
        if (albumArt != null) getAverageColor(albumArt!!) else generateColorFromText(if (currentIndex in tracks.indices) tracks[currentIndex].title else null)
    }
    val animatedBgColor by animateColorAsState(targetValue = targetBackgroundColor, animationSpec = tween(durationMillis = 1000), label = "BgTransition")

    fun playTrack(index: Int) { if (player != null && index in tracks.indices) { player.seekTo(index, 0L); player.prepare(); player.play() } }
    fun toggleShuffle() { if (player != null) { player.shuffleModeEnabled = !player.shuffleModeEnabled; isShuffleOn = player.shuffleModeEnabled } }
    fun toggleLoop() { if (player != null) { player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE; isLoopOn = player.repeatMode == Player.REPEAT_MODE_ONE } }

    LaunchedEffect(player, isPlaying) {
        if (player == null) return@LaunchedEffect
        while (true) {
            currentPosition = player.currentPosition
            totalDuration = player.duration.coerceAtLeast(0L)
            currentIndex = player.currentMediaItemIndex
            isShuffleOn = player.shuffleModeEnabled
            isLoopOn = player.repeatMode == Player.REPEAT_MODE_ONE
            delay(1000L)
        }
    }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                currentIndex = player.currentMediaItemIndex
                isPlaying = player.isPlaying
                isShuffleOn = player.shuffleModeEnabled
                isLoopOn = player.repeatMode == Player.REPEAT_MODE_ONE
                currentPosition = player.currentPosition
                totalDuration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Box(modifier = Modifier.fillMaxSize().background(animatedBgColor)) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(260.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E1E1E).copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                if (countdown > 0) Text(text = countdown.toString(), fontSize = 80.sp, color = Color.Green)
                else if (albumArt != null) Image(bitmap = albumArt!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(text = "🎵", fontSize = 100.sp, color = Color.White)
            }
            Spacer(modifier = Modifier.height(16.dp))
            VintageVuMeter(isPlaying = isPlaying)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = if (currentIndex in tracks.indices) tracks[currentIndex].title else "Выберите трек", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            if (currentIndex in tracks.indices) {
                Column(modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (isShuffleOn) Color.Green.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f)).clickable { toggleShuffle() }, contentAlignment = Alignment.Center) {
                        Text(text = "⇄", color = if (isShuffleOn) Color.Green else Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (-2).dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    val progressFactor = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f
                    Slider(value = progressFactor, onValueChange = { player?.seekTo((it * totalDuration).toLong()) }, modifier = Modifier.height(20.dp), colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green, inactiveTrackColor = Color.White.copy(alpha = 0.2f)))
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = formatTime(currentPosition), color = Color.LightGray, fontSize = 11.sp); Text(text = formatTime(totalDuration), color = Color.LightGray, fontSize = 11.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { if (countdown == 0) { scope.launch { countdown = 5; while (countdown > 0) { delay(1000); countdown-- }; player?.play() } } }, colors = ButtonDefaults.buttonColors(containerColor = if (countdown > 0) Color.Green.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp)) {
                Text(if (countdown > 0) "Запуск через $countdown..." else "⏳ +5 сек отсрочка", color = Color.White)
            }
            Spacer(modifier = Modifier.height(28.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { toggleLoop() }, modifier = Modifier.padding(end = 16.dp)) { Text(text = "🔁", color = if (isLoopOn) Color.Green else Color.White.copy(alpha = 0.4f), fontSize = 22.sp) }
                IconButton(onClick = { if (currentIndex > 0) playTrack(currentIndex - 1) }, modifier = Modifier.padding(end = 20.dp)) { Text("◀◀", color = Color.White, fontSize = 24.sp) }
                Button(onClick = { if (player != null) { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) player.prepare(); player.play() } } }, modifier = Modifier.size(70.dp), shape = RoundedCornerShape(35.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White)) { Text(if (isPlaying) "||" else "▶", color = Color.Black, fontSize = 24.sp) }
                IconButton(onClick = { if (currentIndex + 1 < tracks.size) playTrack(currentIndex + 1) }, modifier = Modifier.padding(start = 20.dp)) { Text("▶▶", color = Color.White, fontSize = 24.sp) }
            }
        }

        // Кнопки вверху
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            Button(onClick = { showEq = true }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { 
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("EQ", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (sleepTimerSeconds > 0) Text("${sleepTimerSeconds/60}м", color = Color.Green, fontSize = 10.sp)
                }
            }
            Button(onClick = { showPlaylist = true }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("☰", color = Color.White, fontSize = 24.sp) }
        }

        if (scrimAlpha > 0f) { Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showPlaylist = false; showEq = false }) }

        // Шторка Плейлиста
        Box(modifier = Modifier.fillMaxWidth().height(sheetHeight).align(Alignment.BottomCenter).offset(y = playlistOffset).background(Color(0xFF1E1E1E), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Box(modifier = Modifier.width(40.dp).height(4.dp).background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp)).align(Alignment.CenterHorizontally))
                Spacer(modifier = Modifier.height(16.dp)); Text("Плейлист", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) { itemsIndexed(tracks) { index, track -> val isCurrent = index == currentIndex; ListItem(headlineContent = { Text(track.title, color = if (isCurrent) Color.Green else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(track.durationStr, color = Color.Gray) }, modifier = Modifier.clickable { playTrack(index); showPlaylist = false }.background(if (isCurrent) Color.White.copy(alpha = 0.05f) else Color.Transparent), colors = ListItemDefaults.colors(containerColor = Color.Transparent) ) } }
            }
        }

        // Шторка Эквалайзера + Таймер сна
        Box(modifier = Modifier.fillMaxWidth().height(sheetHeight).align(Alignment.BottomCenter).offset(y = eqOffset).background(Color(0xFF1E1E1E), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.width(40.dp).height(4.dp).background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.height(16.dp)); Text("Звуковые эффекты", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Таймер сна
                Column(modifier = Modifier.fillMaxWidth(0.9f).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Таймер сна", color = Color.LightGray, fontSize = 14.sp)
                    Text(
                        text = if (sleepTimerSeconds > 0) "Выключение через: ${sleepTimerSeconds / 60}:${String.format(Locale.getDefault(), "%02d", sleepTimerSeconds % 60)}" else "Таймер выключен",
                        color = if (sleepTimerSeconds > 0) Color.Green else Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = (sleepTimerSeconds / 60).toFloat(),
                        onValueChange = { sleepTimerSeconds = it.toLong() * 60 },
                        valueRange = 0f..120f,
                        steps = 24, // Шаги по 5 минут
                        colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
                    )
                }

                if (equalizer != null) {
                    val bands = equalizer.numberOfBands.toInt()
                    val range = equalizer.bandLevelRange
                    val minLevel = range[0].toFloat()
                    val maxLevel = range[1].toFloat()

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Bass Boost Slider
                    Column(modifier = Modifier.fillMaxWidth(0.9f), horizontalAlignment = Alignment.CenterHorizontally) {
                        var bStrength by remember { mutableFloatStateOf(prefs.getInt("bass_strength", 0).toFloat()) }
                        Text(text = "Bass Boost: ${(bStrength/10).toInt()}%", color = Color.White, fontSize = 14.sp)
                        Slider(
                            value = bStrength,
                            onValueChange = { 
                                bStrength = it
                                bassBoost?.setStrength(it.toInt().toShort())
                                prefs.edit().putInt("bass_strength", it.toInt()).apply()
                            },
                            valueRange = 0f..1000f,
                            colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (band in 0 until bands.coerceAtMost(5)) {
                            val freq = equalizer.getCenterFreq(band.toShort()) / 1000
                            var level by remember { mutableFloatStateOf(equalizer.getBandLevel(band.toShort()).toFloat()) }
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(text = if (freq < 1000) "${freq}Hz" else "${freq/1000}kHz", color = Color.Gray, fontSize = 10.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Slider(
                                    value = level,
                                    onValueChange = { 
                                        level = it
                                        equalizer.setBandLevel(band.toShort(), it.toInt().toShort())
                                        prefs.edit().putInt("eq_band_$band", it.toInt()).apply()
                                    },
                                    valueRange = minLevel..maxLevel,
                                    modifier = Modifier.height(180.dp).graphicsLayer(rotationZ = -90f),
                                    colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "${(level/100).toInt()}dB", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text("Эквалайзер недоступен. Начните воспроизведение.", color = Color.Gray)
                }
            }
        }
    }
    if (showPlaylist || showEq) BackHandler { showPlaylist = false; showEq = false }
}

@Composable
fun VintageVuMeter(isPlaying: Boolean) {
    val segmentCount = 12
    var leftLevel by remember { mutableFloatStateOf(0f) }
    var rightLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) { while (leftLevel > 0f || rightLevel > 0f) { leftLevel = max(0f, leftLevel - 0.15f); rightLevel = max(0f, rightLevel - 0.15f); delay(30) }; return@LaunchedEffect }
        while (true) {
            val targetLeft = if (Random.nextFloat() > 0.15f) Random.nextFloat() * 0.95f else 0f
            val targetRight = if (Random.nextFloat() > 0.15f) Random.nextFloat() * 0.95f else 0f
            leftLevel = if (targetLeft > leftLevel) targetLeft else leftLevel - (leftLevel - targetLeft) * 0.25f
            rightLevel = if (targetRight > rightLevel) targetRight else rightLevel - (rightLevel - targetRight) * 0.25f
            delay(40)
        }
    }
    Column(modifier = Modifier.fillMaxWidth(0.85f).background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(text = "L", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp)); Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) { for (i in 0 until segmentCount) { VuSegment(index = i, total = segmentCount, isActive = leftLevel * segmentCount > i) } } }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(text = "R", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp)); Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) { for (i in 0 until segmentCount) { VuSegment(index = i, total = segmentCount, isActive = rightLevel * segmentCount > i) } } }
    }
}

@Composable
fun RowScope.VuSegment(index: Int, total: Int, isActive: Boolean) {
    val baseColor = when { index < 8 -> Color(0xFF00FF44); index < 10 -> Color(0xFFFFD700); else -> Color(0xFFFF2222) }
    val segmentColor = if (isActive) baseColor else baseColor.copy(alpha = 0.08f)
    Box(modifier = Modifier.weight(1f).height(6.dp).then(if (isActive) Modifier.shadow(elevation = 6.dp, shape = RoundedCornerShape(1.dp), ambientColor = baseColor, spotColor = baseColor) else Modifier).background(segmentColor, RoundedCornerShape(1.dp)))
}

private fun getAverageColor(bitmap: Bitmap): Color { return try { val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 1, 1, true); val colorInt = scaledBitmap.getPixel(0, 0); val hsv = FloatArray(3); android.graphics.Color.colorToHSV(colorInt, hsv); hsv[1] = hsv[1].coerceAtMost(0.35f); hsv[2] = hsv[2].coerceAtMost(0.12f); Color(android.graphics.Color.HSVToColor(hsv)) } catch (e: Exception) { Color(0xFF121212) } }
private fun generateColorFromText(text: String?): Color { if (text == null || text == "Выберите трек") return Color(0xFF121212); val hash = text.hashCode(); val hue = (hash % 360).let { if (it < 0) it + 360 else it }.toFloat(); val hsv = floatArrayOf(hue, 0.30f, 0.10f); return Color(android.graphics.Color.HSVToColor(hsv)) }
