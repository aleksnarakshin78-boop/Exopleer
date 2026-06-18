package com.example.exopleer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var isShuffleOn by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }

    var showPlaylist by remember { mutableStateOf(false) }

    // Состояние для хранения извлеченной обложки MP3-файла
    var albumArt by remember { mutableStateOf<Bitmap?>(null) }

    // Таймер отсрочки
    val scope = rememberCoroutineScope()
    var countdown by remember { mutableIntStateOf(0) }

    // Конфигурация для кастомной шторки плейлиста
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val sheetHeight = screenHeight * 0.7f

    // Анимация выплывания шторки
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

    // Извлечение обложки в фоне при переключении треков
    LaunchedEffect(currentIndex, tracks) {
        if (currentIndex in tracks.indices) {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, tracks[currentIndex].uri)
                    val artBytes = retriever.embeddedPicture
                    albumArt = if (artBytes != null) {
                        BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    albumArt = null
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {
                        // Игнорируем ошибки при закрытии потока метаданных
                    }
                }
            }
        } else {
            albumArt = null
        }
    }

    // Вычисление динамического фонового цвета
    val currentTrackTitle = if (currentIndex in tracks.indices) tracks[currentIndex].title else null
    val targetBackgroundColor = remember(albumArt, currentTrackTitle) {
        if (albumArt != null) {
            getAverageColor(albumArt!!)
        } else {
            generateColorFromText(currentTrackTitle)
        }
    }

    // Анимация плавного изменения цвета фона
    val animatedBgColor by animateColorAsState(
        targetValue = targetBackgroundColor,
        animationSpec = tween(durationMillis = 1000),
        label = "BackgroundColorTransition"
    )

    fun playTrack(index: Int) {
        if (player != null && index in tracks.indices) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    fun toggleShuffle() {
        if (player != null) {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            isShuffleOn = player.shuffleModeEnabled
        }
    }

    LaunchedEffect(player, isPlaying) {
        if (player == null) return@LaunchedEffect
        while (true) {
            currentPosition = player.currentPosition
            totalDuration = player.duration.coerceAtLeast(0L)
            currentIndex = player.currentMediaItemIndex
            isShuffleOn = player.shuffleModeEnabled
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
                currentPosition = player.currentPosition
                totalDuration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        currentIndex = player.currentMediaItemIndex
        isPlaying = player.isPlaying
        isShuffleOn = player.shuffleModeEnabled
        currentPosition = player.currentPosition
        totalDuration = player.duration.coerceAtLeast(0L)
        onDispose { player.removeListener(listener) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBgColor) // Применяем анимированный фоновый цвет ко всему экрану
    ) {
        // Контент плеера
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Квадратный контейнер для обложки / таймера / дефолтной ноты
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E).copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (countdown > 0) {
                    Text(
                        text = countdown.toString(),
                        fontSize = 80.sp,
                        color = Color.Green
                    )
                } else if (albumArt != null) {
                    Image(
                        bitmap = albumArt!!.asImageBitmap(),
                        contentDescription = "Обложка альбома",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = "🎵",
                        fontSize = 100.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ВИНТАЖНЫЙ СВЕТОДИОДНЫЙ ИНДИКАТОР УРОВНЯ ЗВУКА (VU METER)
            VintageVuMeter(isPlaying = isPlaying)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (currentIndex in tracks.indices) tracks[currentIndex].title else "Выберите трек",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (currentIndex in tracks.indices) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Полностью отцентрированная круглая кнопка Shuffle над слайдером
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                color = if (isShuffleOn) Color.Green.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f)
                            )
                            .clickable { toggleShuffle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⇄",
                            color = if (isShuffleOn) Color.Green else Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.offset(y = (-2).dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formatTime(currentPosition), color = Color.LightGray, fontSize = 11.sp)
                        Text(text = formatTime(totalDuration), color = Color.LightGray, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                    containerColor = if (countdown > 0) Color.Green.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (countdown > 0) "Запуск через $countdown..." else "⏳ +5 сек отсрочка", color = Color.White)
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Панель управления (Предыдущий, Старт, Следующий)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (currentIndex > 0) playTrack(currentIndex - 1) },
                    modifier = Modifier.padding(end = 24.dp)
                ) {
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

                IconButton(
                    onClick = { if (currentIndex + 1 < tracks.size) playTrack(currentIndex + 1) },
                    modifier = Modifier.padding(start = 24.dp)
                ) {
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
                .clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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

    if (showPlaylist) {
        BackHandler { showPlaylist = false }
    }
}

/**
 * Рендеринг винтажного светодиодного индикатора уровня звука (двухканального Stereo VU Meter).
 */
@Composable
fun VintageVuMeter(isPlaying: Boolean) {
    // Количество светодиодов на один канал
    val segmentCount = 12

    // Хранение текущих уровней Left и Right каналов с физикой инерции
    var leftLevel by remember { mutableFloatStateOf(0f) }
    var rightLevel by remember { mutableFloatStateOf(0f) }

    // Анимационный бесконечный цикл обновления физики индикации
    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            // Если пауза — плавно тушим светодиоды
            while (leftLevel > 0f || rightLevel > 0f) {
                leftLevel = max(0f, leftLevel - 0.15f)
                rightLevel = max(0f, rightLevel - 0.15f)
                delay(30)
            }
            return@LaunchedEffect
        }

        // Если играет музыка — генерируем динамический винтажный такт
        var targetLeft = 0f
        var targetRight = 0f
        while (true) {
            // Симулируем всплески частот (с небольшим расхождением каналов для стерео-эффекта)
            if (Random.nextFloat() > 0.15f) {
                targetLeft = Random.nextFloat() * 0.95f
                targetRight = Random.nextFloat() * 0.95f
                // Изредка залетаем в пиковое красное значение (клиппинг)
                if (Random.nextFloat() > 0.85f) {
                    targetLeft = 1f
                }
                if (Random.nextFloat() > 0.85f) {
                    targetRight = 1f
                }
            }

            // Инерционное сглаживание: подъем мгновенный, падение плавное
            leftLevel = if (targetLeft > leftLevel) targetLeft else leftLevel - (leftLevel - targetLeft) * 0.25f
            rightLevel = if (targetRight > rightLevel) targetRight else rightLevel - (rightLevel - targetRight) * 0.25f

            delay(40) // Частота кадров (~25 FPS)
        }
    }

    // Внешний корпус шкалы индикатора (в стиле металлических ретро-панелей)
    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Канал L (Левый)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "L",
                color = Color.Gray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(14.dp)
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (i in 0 until segmentCount) {
                    val isActive = leftLevel * segmentCount > i
                    VuSegment(index = i, total = segmentCount, isActive = isActive)
                }
            }
        }

        // Канал R (Правый)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "R",
                color = Color.Gray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(14.dp)
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (i in 0 until segmentCount) {
                    val isActive = rightLevel * segmentCount > i
                    VuSegment(index = i, total = segmentCount, isActive = isActive)
                }
            }
        }
    }
}

/**
 * Одиночный светодиод (сегмент шкалы) со своим цветом и эффектом неонового свечения (Glow).
 */
@Composable
fun RowScope.VuSegment(index: Int, total: Int, isActive: Boolean) {
    // Определяем цвет сегмента по классической винтажной шкале децибелов:
    // 0..7 (зеленые — норма), 8..9 (желтые — предупреждение), 10..11 (красные — перегрузка)
    val baseColor = when {
        index < 8 -> Color(0xFF00FF44) // Ярко-зеленый
        index < 10 -> Color(0xFFFFD700) // Золотисто-желтый
        else -> Color(0xFFFF2222) // Аварийно-красный
    }

    // Эффект свечения: если сегмент активен — горит сочно, если выключен — глубокий тусклый оттенок
    val segmentColor = if (isActive) baseColor else baseColor.copy(alpha = 0.08f)

    // Эффект тени-свечения (Glow) только для горящих светодиодов
    val glowModifier = if (isActive) {
        Modifier.shadow(
            elevation = 6.dp,
            shape = RoundedCornerShape(1.dp),
            ambientColor = baseColor,
            spotColor = baseColor
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .height(6.dp)
            .then(glowModifier)
            .background(segmentColor, RoundedCornerShape(1.dp))
    )
}

/**
 * Извлекает средний цвет обложки, мутирует его (делает темным и не насыщенным) для фона
 */
private fun getAverageColor(bitmap: Bitmap): Color {
    return try {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val colorInt = scaledBitmap.getPixel(0, 0)

        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(colorInt, hsv)

        hsv[1] = hsv[1].coerceAtMost(0.35f)
        hsv[2] = hsv[2].coerceAtMost(0.12f)

        Color(android.graphics.Color.HSVToColor(hsv))
    } catch (e: Exception) {
        Color(0xFF121212)
    }
}

/**
 * Генерирует стабильный глубокий оттенок на основе хэш-кода названия песни
 */
private fun generateColorFromText(text: String?): Color {
    if (text == null || text == "Выберите трек") return Color(0xFF121212)

    val hash = text.hashCode()
    val hue = (hash % 360).let { if (it < 0) it + 360 else it }.toFloat()

    val hsv = floatArrayOf(
        hue,
        0.30f,
        0.10f
    )

    return Color(android.graphics.Color.HSVToColor(hsv))
}