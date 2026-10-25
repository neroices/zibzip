package dev.neroices.zibzip.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.neroices.zibzip.domain.StorageVolumeInfo
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.zibzipApp
import java.io.File

private data class StorageShortcut(
    val title: String,
    val folderName: String,
    val icon: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageBottomSheet(
    volumes: List<StorageVolumeInfo>,
    useTrash: Boolean = false,
    onOpen: (String) -> Unit,
    onOpenTrash: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val primary = remember { context.zibzipApp.storage.primaryRoot }
    val cs = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = cs.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Storage volume cards
            volumes.forEach { volume ->
                VolumeCard(volume = volume) {
                    onOpen(volume.path)
                }
            }

            if (useTrash) {
                Surface(
                    onClick = onOpenTrash,
                    shape = RoundedCornerShape(20.dp),
                    color = cs.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = cs.primaryContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = cs.onPrimaryContainer,
                                modifier = Modifier
                                    .padding(9.dp)
                                    .fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Trash",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = cs.onSurface,
                            )
                            Text(
                                text = "Restore or permanently delete items",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Quick shortcuts header
            Text(
                text = "Quick shortcuts",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = cs.primary,
                modifier = Modifier.padding(start = 2.dp, top = 4.dp),
            )

            val shortcuts = remember {
                listOf(
                    StorageShortcut("Download", "Download", Icons.Outlined.Download),
                    StorageShortcut("Camera", "DCIM", Icons.Outlined.PhotoCamera),
                    StorageShortcut("Pictures", "Pictures", Icons.Outlined.Image),
                    StorageShortcut("Documents", "Documents", Icons.Outlined.Description),
                    StorageShortcut("Music", "Music", Icons.Outlined.MusicNote),
                    StorageShortcut("Movies", "Movies", Icons.Outlined.Movie),
                )
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    shortcuts.forEachIndexed { index, shortcut ->
                        val targetDir = remember(primary, shortcut.folderName) {
                            File(primary, shortcut.folderName).path
                        }
                        ShortcutRow(
                            title = shortcut.title,
                            icon = shortcut.icon,
                            onClick = { onOpen(targetDir) },
                        )
                        if (index < shortcuts.lastIndex) {
                            HorizontalDivider(
                                color = cs.outlineVariant.copy(alpha = 0.25f),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(
    volume: StorageVolumeInfo,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val used = (volume.totalBytes - volume.freeBytes).coerceAtLeast(0)
    val fraction = if (volume.totalBytes > 0) (used.toFloat() / volume.totalBytes).coerceIn(0f, 1f) else 0f

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = cs.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (volume.isRemovable) Icons.Rounded.SdCard else Icons.Rounded.PhoneAndroid,
                        contentDescription = null,
                        tint = cs.onPrimaryContainer,
                        modifier = Modifier
                            .padding(9.dp)
                            .fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = volume.label,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = cs.onSurface,
                    )
                    Text(
                        text = "${formatSize(volume.freeBytes)} free of ${formatSize(volume.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }

            if (volume.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerHighest,
                )
            }
        }
    }
}

@Composable
private fun ShortcutRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = cs.primaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize(),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}
