package com.datarohit.luma.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.datarohit.luma.appGraph
import com.datarohit.luma.camera.Icon
import com.datarohit.luma.camera.LumaIcon
import com.datarohit.luma.core.storage.LumaMediaRepository
import com.datarohit.luma.ui.design.Chevron
import com.datarohit.luma.ui.design.Ios
import com.datarohit.luma.ui.design.IosActionSheet
import com.datarohit.luma.ui.design.IosDivider
import com.datarohit.luma.ui.design.IosNavBar
import com.datarohit.luma.ui.design.IosRow
import com.datarohit.luma.ui.design.IosSection
import com.datarohit.luma.ui.design.IosSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/** Output class badge text from the stored description (spec RULE-3). */
fun outputClassOf(item: LumaMediaRepository.Item): String = when {
    item.isVideo -> "Video"
    item.mime.contains("dng") && item.description?.contains("Merged RAW") == true -> "Merged RAW"
    item.mime.contains("dng") -> "Native RAW"
    item.description?.contains("Panorama") == true -> "Panorama"
    item.description?.contains("camera HAL") == true -> "Processed by camera"
    else -> "Processed by Luma"
}

@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { context.appGraph.media }
    var items by remember { mutableStateOf<List<LumaMediaRepository.Item>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(refresh) {
        items = withContext(Dispatchers.IO) { runCatching { repo.list() }.getOrDefault(emptyList()) }
    }

    val list = items
    val index = viewerIndex
    if (list != null && index != null && list.isNotEmpty()) {
        BackHandler { viewerIndex = null }
        Viewer(list, index.coerceIn(0, list.size - 1), onClose = { viewerIndex = null }, onDeleted = {
            viewerIndex = null
            refresh++
        })
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Ios.Background).statusBarsPadding()) {
        IosNavBar("", "Camera", onBack)
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ios.Accent) }
            list.isEmpty() -> Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(LumaIcon.GALLERY, Ios.Label3, size = 56.dp)
                Spacer(Modifier.height(16.dp))
                Text("No Photos or Videos", style = Ios.Title2, color = Ios.Label)
                Spacer(Modifier.height(8.dp))
                Text("Photos are saved to DCIM/Luma and videos to Movies/Luma.", style = Ios.Subhead, color = Ios.Label2, textAlign = TextAlign.Center)
            }
            else -> {
                val photos = list.count { !it.isVideo }
                val videos = list.size - photos
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(span = { GridItemSpan(3) }) {
                        Column(Modifier.padding(start = 20.dp, bottom = 12.dp)) {
                            Text("Library", style = Ios.LargeTitle, color = Ios.Label)
                            Spacer(Modifier.height(4.dp))
                            Text(countLabel(photos, videos), style = Ios.Subhead, color = Ios.Label2)
                        }
                    }
                    items(list, key = { it.uri.toString() }) { item ->
                        val i = list.indexOf(item)
                        Box(Modifier.aspectRatio(1f).clickable { viewerIndex = i }) {
                            Thumbnail(item)
                            if (item.isVideo) {
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
                                Text(formatDuration(item.durationMs), style = Ios.Caption2, color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
                            }
                            if (item.mime.contains("dng")) Badge("RAW", Modifier.align(Alignment.TopEnd))
                            if (item.description?.contains("Panorama") == true) Badge("PANO", Modifier.align(Alignment.TopEnd))
                        }
                    }
                }
            }
        }
    }
}

private fun countLabel(photos: Int, videos: Int): String = listOfNotNull(
    photos.takeIf { it > 0 }?.let { "$it ${if (it == 1) "Photo" else "Photos"}" },
    videos.takeIf { it > 0 }?.let { "$it ${if (it == 1) "Video" else "Videos"}" },
).joinToString(", ")

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Text(text, style = Ios.Caption2.copy(fontWeight = FontWeight.Bold), color = Color.Black,
        modifier = modifier.padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Ios.Accent).padding(horizontal = 4.dp, vertical = 2.dp))
}

@Composable
private fun Thumbnail(item: LumaMediaRepository.Item) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(item.uri, Size(360, 360), null) }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) Image(b.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    else Box(Modifier.fillMaxSize().background(Ios.Grouped))
}

@Composable
private fun Viewer(list: List<LumaMediaRepository.Item>, start: Int, onClose: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start) { list.size }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    val current = list[pager.currentPage.coerceIn(0, list.size - 1)]

    Box(Modifier.fillMaxSize().background(if (chrome) Ios.Background else Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { chrome = !chrome } }) { page -> FullImage(list[page]) }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar: back, date and time.
                Column(Modifier.fillMaxWidth().background(Color(0xCC000000)).statusBarsPadding()) {
                    Box(Modifier.fillMaxWidth().height(52.dp)) {
                        Row(Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClose).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Chevron(Ios.Accent, left = true, size = 20.dp)
                            Text("Library", style = Ios.Body, color = Ios.Accent)
                        }
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(DateFormat.getMediumDateFormat(context).format(Date(current.dateTaken)), style = Ios.Headline, color = Ios.Label)
                            Text(DateFormat.getTimeFormat(context).format(Date(current.dateTaken)), style = Ios.Caption, color = Ios.Label2)
                        }
                    }
                }
                // Bottom toolbar: share, info, delete.
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).navigationBarsPadding().height(56.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarIcon(LumaIcon.SHARE) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = current.mime
                            putExtra(Intent.EXTRA_STREAM, current.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                    ToolbarIcon(LumaIcon.INFO) { showInfo = true }
                    ToolbarIcon(LumaIcon.DELETE) { confirmDelete = true }
                }
            }
        }
        IosSheet(showInfo, "Info", onDismiss = { showInfo = false }, maxHeight = 460) { InfoContent(current) }
        IosActionSheet(
            visible = confirmDelete,
            message = "This ${if (current.isVideo) "video" else "photo"} will be deleted from ${if (current.isVideo) "Movies" else "DCIM"}/Luma.",
            actions = listOf(Triple("Delete ${if (current.isVideo) "Video" else "Photo"}", true) {
                confirmDelete = false
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { context.appGraph.media.delete(current.uri) } }
                    onDeleted()
                }
            }),
            onCancel = { confirmDelete = false },
        )
    }
}

@Composable
private fun ToolbarIcon(icon: LumaIcon, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, Ios.Accent, size = 24.dp)
    }
}

@Composable
private fun InfoContent(item: LumaMediaRepository.Item) {
    val context = LocalContext.current
    IosSection(footer = "No location is stored in Luma files.") {
        IosRow("Name", value = item.name)
        IosDivider()
        IosRow("Kind", value = outputClassOf(item))
        if (item.width > 0) {
            IosDivider()
            IosRow("Dimensions", value = "${item.width} × ${item.height}" +
                if (!item.isVideo) String.format(Locale.ROOT, " · %.1f MP", item.width.toLong() * item.height / 1e6) else "")
        }
        if (item.isVideo) {
            IosDivider()
            IosRow("Duration", value = formatDuration(item.durationMs))
        }
        IosDivider()
        IosRow("Size", value = Formatter.formatShortFileSize(context, item.size))
        IosDivider()
        IosRow("Type", value = item.mime)
    }
    item.description?.takeIf { it.isNotBlank() }?.let {
        IosSection("Capture") {
            Text(it.removePrefix("Luma: "), style = Ios.Subhead, color = Ios.Label, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun FullImage(item: LumaMediaRepository.Item) {
    if (item.isVideo) {
        VideoPlayer(item)
        return
    }
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, item.uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val maxSide = 2560
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > maxSide) {
                        val scale = maxSide.toFloat() / longest
                        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                }
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(color = Ios.Accent)
    }
}

/** Plays a Luma video in place (platform VideoView with transport controls). */
@Composable
private fun VideoPlayer(item: LumaMediaRepository.Item) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                val controller = android.widget.MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(item.uri)
                setOnPreparedListener { start() }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
