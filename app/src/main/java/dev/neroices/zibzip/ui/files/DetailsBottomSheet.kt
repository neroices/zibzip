package dev.neroices.zibzip.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.neroices.zibzip.data.FolderStats
import dev.neroices.zibzip.domain.FileItem
import dev.neroices.zibzip.domain.FileKind
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.domain.itemsLabel
import dev.neroices.zibzip.ui.components.formatDate
import dev.neroices.zibzip.ui.components.kindVisual
import dev.neroices.zibzip.ui.components.mouseDragScroll
import dev.neroices.zibzip.ui.components.rememberThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsBottomSheet(
    items: List<FileItem>,
    folderStats: suspend (String) -> FolderStats,
    onDismiss: () -> Unit,
    calculateSha256: (suspend (String) -> String)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }

    var bytes by remember { mutableStateOf<Long?>(null) }
    var files by remember { mutableIntStateOf(0) }
    var folders by remember { mutableIntStateOf(0) }

    val single = items.singleOrNull()
    val isSingleFile = single != null && !single.isDirectory
    val autoCalculate = isSingleFile && single.size <= 50 * 1024 * 1024
    var sha256Triggered by remember(single?.path) { mutableStateOf(autoCalculate) }
    var sha256 by remember(single?.path) { mutableStateOf<String?>(null) }
    var sha256Loading by remember(single?.path) { mutableStateOf(false) }
    var sha256Error by remember(single?.path) { mutableStateOf(false) }
    var copiedSha256 by remember { mutableStateOf(false) }
    var copiedPath by remember { mutableStateOf(false) }

    LaunchedEffect(copiedSha256) {
        if (copiedSha256) {
            delay(2000)
            copiedSha256 = false
        }
    }

    LaunchedEffect(copiedPath) {
        if (copiedPath) {
            delay(2000)
            copiedPath = false
        }
    }

    LaunchedEffect(single?.path, sha256Triggered) {
        if (isSingleFile && sha256Triggered && sha256 == null) {
            sha256Loading = true
            sha256Error = false
            val result = runCatching {
                if (calculateSha256 != null) {
                    calculateSha256(single.path)
                } else {
                    withContext(Dispatchers.IO) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        File(single.path).inputStream().buffered().use { stream ->
                            var read: Int
                            while (stream.read(buffer).also { read = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                digest.update(buffer, 0, read)
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }
            }.getOrNull()
            if (result != null) {
                sha256 = result
            } else {
                sha256Error = true
            }
            sha256Loading = false
        }
    }

    LaunchedEffect(items) {
        var b = 0L
        var f = 0
        var d = 0
        for (item in items) {
            if (item.isDirectory) {
                d++
                val s = runCatching { folderStats(item.path) }.getOrNull()
                if (s != null) {
                    b += s.bytes
                    f += s.files
                    d += s.folders
                }
            } else {
                f++
                b += item.size
            }
            bytes = b
            files = f
            folders = d
        }
        bytes = b
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 440.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = cs.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .padding(horizontal = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
                .verticalScroll(scrollState)
                .mouseDragScroll(scrollState),
        ) {
            // Header card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (single != null) {
                    val visual = kindVisual(single.kind)
                    val thumbnail by rememberThumbnail(single.path, single.kind, single.lastModified)
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(visual.container),
                        contentAlignment = Alignment.Center,
                    ) {
                        val thumb = thumbnail
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                            if (single.kind == FileKind.Video) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(Color.Black.copy(alpha = 0.28f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            } else if (single.kind == FileKind.Audio) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(Color.Black.copy(alpha = 0.28f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        } else {
                            Icon(
                                imageVector = visual.icon,
                                contentDescription = null,
                                tint = visual.content,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = single.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = cs.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (single.isDirectory) "Folder" else single.typeLabel.ifEmpty { "File" },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(cs.secondaryContainer.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = cs.secondary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = itemsLabel(items.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = cs.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = bytes?.let { formatSize(it) } ?: "Calculating…",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = cs.outlineVariant.copy(alpha = 0.4f),
            )

            // Properties list
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val curBytes = bytes
                val sizeText = when {
                    single != null && !single.isDirectory -> {
                        "${formatSize(single.size)}  (${String.format(Locale.US, "%,d", single.size)} bytes)"
                    }
                    curBytes != null -> {
                        "${formatSize(curBytes)}  (${String.format(Locale.US, "%,d", curBytes)} bytes)"
                    }
                    else -> "Calculating…"
                }
                PropertyItem(
                    icon = Icons.Rounded.Storage,
                    label = "Size",
                    value = sizeText,
                )

                // Contains (if directory or multiple items)
                if (single == null || single.isDirectory) {
                    PropertyItem(
                        icon = Icons.Rounded.Folder,
                        label = "Contains",
                        value = "$files files, $folders folders",
                    )
                }

                // Date Modified
                if (single != null) {
                    PropertyItem(
                        icon = Icons.Rounded.CalendarToday,
                        label = "Modified",
                        value = formatDate(single.lastModified),
                    )
                }

                // Location (parent folder)
                if (single != null) {
                    PropertyItem(
                        icon = Icons.Rounded.FolderOpen,
                        label = "Location",
                        value = single.parentPath.ifEmpty { "/" },
                    )
                }

                // Full Path with Copy
                if (single != null) {
                    val copyPathAction: () -> Unit = {
                        clipboard?.setPrimaryClip(ClipData.newPlainText("File Path", single.path))
                        Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                        copiedPath = true
                    }
                    PropertyItem(
                        icon = Icons.Rounded.Description,
                        label = "Path",
                        value = single.path,
                        trailingContent = {
                            IconButton(
                                onClick = copyPathAction,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = if (copiedPath) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy Path",
                                    tint = if (copiedPath) cs.primary else cs.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = copyPathAction,
                    )
                }

                // SHA-256 for single files
                if (isSingleFile) {
                    val copySha256Action: () -> Unit = {
                        sha256?.let { hash ->
                            clipboard?.setPrimaryClip(ClipData.newPlainText("SHA-256", hash))
                            Toast.makeText(context, "SHA-256 copied to clipboard", Toast.LENGTH_SHORT).show()
                            copiedSha256 = true
                        }
                    }

                    PropertyItem(
                        icon = Icons.Rounded.Fingerprint,
                        label = "SHA-256",
                        content = {
                            val hash = sha256
                            when {
                                hash != null -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(onClick = copySha256Action),
                                    ) {
                                        Text(
                                            text = hash,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                            ),
                                            color = cs.onSurface,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = copySha256Action,
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (copiedSha256) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                                contentDescription = "Copy SHA-256",
                                                tint = if (copiedSha256) cs.primary else cs.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                sha256Loading -> {
                                    Text(
                                        text = "Calculating…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = cs.primary,
                                    )
                                }
                                sha256Error -> {
                                    Text(
                                        text = "Unavailable",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = cs.error,
                                    )
                                }
                                else -> {
                                    Text(
                                        text = "Calculate",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = cs.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        modifier = Modifier.clickable { sha256Triggered = true },
                                    )
                                }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Close", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PropertyItem(
    icon: ImageVector,
    label: String,
    value: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            if (content != null) {
                content()
            } else if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                )
            }
        }
        if (trailingContent != null) {
            trailingContent()
        }
    }
}
