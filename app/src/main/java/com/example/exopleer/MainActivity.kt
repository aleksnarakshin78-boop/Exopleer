package com.example.exopleer

import android.Manifest
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

data class AudioTrack(val uri: Uri, val title: String, val durationMs: Long, val durationStr: String)

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private val tracksState = mutableStateOf<List<AudioTrack>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, MediaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, false)
        }

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
                            tracksState.value = loadAudioFiles()
                        }
                    }

                    if (hasPermission) {
                        LaunchedEffect(Unit) {
                            tracksState.value = loadAudioFiles()
                        }
                        PlayerScreen(player = player, tracks = tracksState.value)
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

    private fun loadAudioFiles(): List<AudioTrack> {
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
        return trackList
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}

@Composable
fun PlayerScreen(player: ExoPlayer?, tracks: List<AudioTrack>) {
    var currentIndex by remember { mutableStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }
    // Функция для перевода миллисекунд в формат "02:30"
    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // 1. Загружаем ВСЮ пачку треков в ExoPlayer один раз при старте
    LaunchedEffect(tracks, player) {
        if (player != null && tracks.isNotEmpty()) {
            player.clearMediaItems()
            val mediaItems = tracks.map { MediaItem.fromUri(it.uri) }
            player.addMediaItems(mediaItems)
            player.prepare()
        }
    }

    // 2. Функция переключения трека (просто прыгаем по системной очереди плеера)
    fun playTrack(index: Int) {
        if (player != null && index in tracks.indices) {
            currentIndex = index
            player.seekTo(index, 0L)
            player.play()
            isPlaying = true
        }
    }
// 3. Обновление прогресс-бара и индекса трека раз в секунду (БЕЗ желтой подсветки!)
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect

        while (true) {
            if (player != null) {
                currentPosition = player.currentPosition
                totalDuration = player.duration.coerceAtLeast(0L)

                // Проверяем актуальность индекса трека
                if (player.currentMediaItemIndex != currentIndex && player.currentMediaItemIndex in tracks.indices) {
                    currentIndex = player.currentMediaItemIndex
                }
            }
            delay(1000L)
        }
    }

    // 4. Ловим автоматический переход на следующий трек и синхронизируем плеер с интерфейсом
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player.currentMediaItemIndex in tracks.indices) {
                    currentIndex = player.currentMediaItemIndex
                }
                isPlaying = player.isPlaying
            }
        }

        player.addListener(listener)

        // Синхронизируем состояние при первом запуске эффекта
        if (player.currentMediaItemIndex in tracks.indices) {
            currentIndex = player.currentMediaItemIndex
        }
        isPlaying = player.isPlaying

        onDispose {
            player.removeListener(listener)
        }
    }

    // Никаких лишних onDispose или скобок здесь быть не должно! Сразу идет Column:
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Список треков
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

        // НАЗВАНИЕ ТРЕКА И ТАЙМЕРЫ (Показываются, только если трек выбран)
        if (currentIndex in tracks.indices) {
            Text(
                text = "Сейчас играет: ${tracks[currentIndex].title}",
                color = Color.Green,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Прогресс-бар (Слайдер) — Простой уменьшенный вариант
            // Тонкий и аккуратный прогресс-бар вместо громоздкого слайдера
            val progressFactor = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f

            androidx.compose.material3.LinearProgressIndicator(
                progress = { progressFactor.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp) // Вот оно, наше значение! Делаем полосочку тонкой и аккуратной
                    .padding(vertical = 0.dp),
                color = Color.Green,
                trackColor = Color.Gray
            )
            // Строка времени: Текущее положение (слева) / Общая длина (справа)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = formatTime(currentPosition), color = Color.Gray)
                Text(text = formatTime(totalDuration), color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Кнопки управления (Оставляем без изменений)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            // Кнопка НАЗАД
            Button(
                onClick = { if (currentIndex > 0) playTrack(currentIndex - 1) },
                modifier = Modifier.padding(8.dp)
            ) {
                Text("◀◀")
            }

            // Кнопка ИГРАТЬ / ПАУЗА
            Button(
                onClick = {
                    if (player != null && currentIndex in tracks.indices) {
                        if (isPlaying) {
                            player.pause()
                        } else {
                            player.play()
                        }
                        isPlaying = !isPlaying
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp).height(50.dp)
            ) {
                Text(if (isPlaying) "ПАУЗА" else "ИГРАТЬ")
            }

            // Кнопка ВПЕРЕД
            Button(
                onClick = { if (currentIndex + 1 < tracks.size) playTrack(currentIndex + 1) },
                modifier = Modifier.padding(8.dp)
            ) {
                Text("▶▶")
            }
        }
    }
}