package com.example.exopleer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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

data class AudioTrack(val uri: Uri, val title: String, val durationMs: Long, val durationStr: String)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val playerState = mutableStateOf<Player?>(null)
    private val tracksState = mutableStateOf<List<AudioTrack>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, MediaService::class.java)
        startService(serviceIntent)

        val sessionToken = SessionToken(this, ComponentName(this, MediaService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            playerState.value = controllerFuture?.get()
        }, MoreExecutors.directExecutor())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    var hasPermission by remember { mutableStateOf(checkAudioPermission()) }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasPermission = isGranted
                    }

                    if (hasPermission) {
                        LaunchedEffect(Unit) {
                            tracksState.value = loadAudioFiles()
                        }
                        PlayerScreen(player = playerState.value, tracks = tracksState.value)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Button(onClick = {
                                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Manifest.permission.READ_MEDIA_AUDIO
                                } else {
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                                permissionLauncher.launch(permission)
                            }) {
                                Text("Разрешить доступ к музыке")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAudioPermission() = ContextCompat.checkSelfPermission(
        this,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private suspend fun loadAudioFiles(): List<AudioTrack> = withContext(Dispatchers.IO) {
        val trackList = mutableListOf<AudioTrack>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION
        )
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
                val durationStr = String.format("%02d:%02d", min, sec)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                trackList.add(AudioTrack(uri, title, durationMs, durationStr))
            }
        }
        trackList
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}

@Composable
fun PlayerScreen(player: Player?, tracks: List<AudioTrack>) {
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    
    var showPlaylist by remember { mutableStateOf(false) }

    // Таймер отсрочки
    val scope = rememberCoroutineScope()
    var countdown by remember { mutableIntStateOf(0) }

    // Конфигурация для кастомной шторки
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val sheetHeight = screenHeight * 0.7f
    
    // Анимация выплывания (длительность 2000мс для экстремально плавного эффекта)
    val offsetY by animateDpAsState(
        targetValue = if (showPlaylist) 0.dp else sheetHeight,
        animationSpec = tween(durationMillis = 6000),
        label = "SheetAnimation"
    )
    
    val scrimAlpha by animateFloatAsState(
        targetValue = if (showPlaylist) 0.6f else 0f,
        animationSpec = tween(durationMillis = 6000),
        label = "ScrimAnimation"
    )

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    LaunchedEffect(tracks, player) {
        if (player != null && tracks.isNotEmpty() && player.mediaItemCount == 0) {
            val mediaItems = tracks.map { MediaItem.fromUri(it.uri) }
            player.setMediaItems(mediaItems)
            player.prepare()
        }
    }

    fun playTrack(index: Int) {
        if (player != null && index in tracks.indices) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    LaunchedEffect(player, isPlaying) {
        if (player == null) return@LaunchedEffect
        while (true) {
            currentPosition = player.currentPosition
            totalDuration = player.duration.coerceAtLeast(0L)
            currentIndex = player.currentMediaItemIndex
            delay(1000L)
        }
    }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                currentIndex = player.currentMediaItemIndex
                isPlaying = player.isPlaying
                currentPosition = player.currentPosition
                totalDuration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        currentIndex = player.currentMediaItemIndex
        isPlaying = player.isPlaying
        currentPosition = player.currentPosition
        totalDuration = player.duration.coerceAtLeast(0L)
        onDispose { player.removeListener(listener) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Контент плеера
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (countdown > 0) countdown.toString() else "🎵",
                    fontSize = if (countdown > 0) 80.sp else 100.sp,
                    color = if (countdown > 0) Color.Green else Color.White
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = if (currentIndex in tracks.indices) tracks[currentIndex].title else "Выберите трек",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (currentIndex in tracks.indices) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val progressFactor = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f
                    Slider(
                        value = progressFactor,
                        onValueChange = { 
                            player?.seekTo((it * totalDuration).toLong())
                        },
                        modifier = Modifier.height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Green,
                            activeTrackColor = Color.Green,
                            inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formatTime(currentPosition), color = Color.Gray, fontSize = 11.sp)
                        Text(text = formatTime(totalDuration), color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (countdown == 0) {
                        scope.launch {
                            countdown = 5
                            while (countdown > 0) {
                                delay(1000)
                                countdown--
                            }
                            player?.play()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (countdown > 0) Color.Green.copy(alpha = 0.2f) else Color(0xFF1E1E1E)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (countdown > 0) "Запуск через $countdown..." else "⏳ +5 сек отсрочка", color = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (currentIndex > 0) playTrack(currentIndex - 1) }) {
                    Text("◀◀", color = Color.White, fontSize = 24.sp)
                }

                Button(
                    onClick = { if (player?.isPlaying == true) player.pause() else player?.play() },
                    modifier = Modifier.size(70.dp),
                    shape = RoundedCornerShape(35.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text(if (isPlaying) "||" else "▶", color = Color.Black, fontSize = 24.sp)
                }

                IconButton(onClick = { if (currentIndex + 1 < tracks.size) playTrack(currentIndex + 1) }) {
                    Text("▶▶", color = Color.White, fontSize = 24.sp)
                }
            }
        }

        // Кнопка плейлиста
        Button(
            onClick = { showPlaylist = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text("☰", color = Color.White, fontSize = 24.sp)
        }

        // --- КАСТОМНАЯ ШТОРКА ---
        
        // Затемнение фона
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showPlaylist = false }
            )
        }

        // Панель плейлиста
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .align(Alignment.BottomCenter)
                .offset(y = offsetY)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {} // Чтобы клики внутри не закрывали шторку
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Полоска-индикатор сверху шторки
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                        .align(Alignment.CenterHorizontally)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    "Плейлист",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(tracks) { index, track ->
                        val isCurrent = index == currentIndex
                        ListItem(
                            headlineContent = { 
                                Text(
                                    track.title, 
                                    color = if (isCurrent) Color.Green else Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                ) 
                            },
                            supportingContent = { Text(track.durationStr, color = Color.Gray) },
                            modifier = Modifier
                                .clickable { 
                                    playTrack(index)
                                    showPlaylist = false
                                }
                                .background(if (isCurrent) Color.White.copy(alpha = 0.05f) else Color.Transparent),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }

    // Обработка кнопки "Назад" для закрытия шторки
    if (showPlaylist) {
        BackHandler { showPlaylist = false }
    }
}
