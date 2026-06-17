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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AudioTrack(val uri: Uri, val title: String, val durationMs: Long, val durationStr: String)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val playerState = mutableStateOf<Player?>(null)
    private val tracksState = mutableStateOf<List<AudioTrack>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запуск сервиса
        val serviceIntent = Intent(this, MediaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Подключение к MediaSession через MediaController
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
                        if (isGranted) {
                            // После получения разрешения загружаем файлы
                            // Мы не можем запустить LaunchedEffect здесь напрямую, но hasPermission изменится
                        }
                    }

                    if (hasPermission) {
                        LaunchedEffect(Unit) {
                            tracksState.value = loadAudioFiles()
                        }
                        PlayerScreen(player = playerState.value, tracks = tracksState.value)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
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

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // Синхронизация списка треков с плеером
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

    // Периодическое обновление позиции прогресса
    LaunchedEffect(player, isPlaying) {
        if (player == null) return@LaunchedEffect
        while (true) {
            currentPosition = player.currentPosition
            totalDuration = player.duration.coerceAtLeast(0L)
            currentIndex = player.currentMediaItemIndex
            delay(1000L)
        }
    }

    // Слушатель событий плеера для мгновенного обновления UI
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
        // Инициализация начальных значений
        currentIndex = player.currentMediaItemIndex
        isPlaying = player.isPlaying
        currentPosition = player.currentPosition
        totalDuration = player.duration.coerceAtLeast(0L)
        
        onDispose { player.removeListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(tracks) { index, track ->
                val isCurrent = index == currentIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            if (isCurrent) Color(0xFF1E3A8A) else Color(0xFF1E1E1E),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { playTrack(index) }
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = track.title,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(text = track.durationStr, color = Color.Gray)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (currentIndex in tracks.indices) {
            Text(
                text = "Сейчас играет: ${tracks[currentIndex].title}",
                color = Color.Green,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            val progressFactor = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f

            LinearProgressIndicator(
                progress = { progressFactor.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Color.Green,
                trackColor = Color.Gray
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = formatTime(currentPosition), color = Color.Gray)
                Text(text = formatTime(totalDuration), color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Button(
                onClick = { if (currentIndex > 0) playTrack(currentIndex - 1) },
                modifier = Modifier.padding(8.dp)
            ) {
                Text("◀◀")
            }

            Button(
                onClick = {
                    if (player != null) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp).height(50.dp)
            ) {
                Text(if (isPlaying) "pause" else "play")
            }

            Button(
                onClick = { if (currentIndex + 1 < tracks.size) playTrack(currentIndex + 1) },
                modifier = Modifier.padding(8.dp)
            ) {
                Text("▶▶")
            }
        }
    }
}
