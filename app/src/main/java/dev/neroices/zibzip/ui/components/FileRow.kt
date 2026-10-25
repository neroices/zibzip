@file:OptIn(ExperimentalFoundationApi::class)

package dev.neroices.zibzip.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.neroices.zibzip.domain.FileKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Immutable
data class KindVisual(val icon: ImageVector, val container: Color, val content: Color)

@Composable
fun kindVisual(kind: FileKind): KindVisual {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        FileKind.Folder -> KindVisual(
            icon = Icons.Rounded.Folder,
            container = c.primaryContainer.copy(alpha = 0.4f),
            content = c.primary,
        )
        FileKind.Archive -> KindVisual(
            icon = Icons.Rounded.FolderZip,
            container = c.primaryContainer.copy(alpha = 0.4f),
            content = c.primary,
        )
        FileKind.Image -> KindVisual(
            icon = Icons.Rounded.Image,
            container = c.secondaryContainer.copy(alpha = 0.4f),
            content = c.secondary,
        )
        FileKind.Video -> KindVisual(
            icon = Icons.Rounded.Movie,
            container = c.secondaryContainer.copy(alpha = 0.4f),
            content = c.secondary,
        )
        FileKind.Audio -> KindVisual(
            icon = Icons.Rounded.MusicNote,
            container = c.tertiaryContainer.copy(alpha = 0.4f),
            content = c.tertiary,
        )
        FileKind.Document -> KindVisual(
            icon = Icons.Rounded.Description,
            container = c.surfaceContainerHighest,
            content = c.onSurfaceVariant,
        )
        FileKind.Code -> KindVisual(
            icon = Icons.Rounded.Code,
            container = c.surfaceContainerHighest,
            content = c.primary,
        )
        FileKind.Apk -> KindVisual(
            icon = Icons.Rounded.Android,
            container = c.primaryContainer.copy(alpha = 0.4f),
            content = c.primary,
        )
        FileKind.Other -> KindVisual(
            icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
            container = c.surfaceContainerHighest,
            content = c.onSurfaceVariant,
        )
    }
}

private val dateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

fun formatDate(epochMillis: Long): String =
    if (epochMillis <= 0) "—" else dateFormat.format(Instant.ofEpochMilli(epochMillis))

/**
 * High performance compact list row (52dp).
 * Tinted 36dp icon tile, name, size/date subtitle, and optional type badge.
 * Selected row swaps tile for a bright primary checkmark.
 */
@Composable
fun FileRow(
    title: String,
    subtitle: String,
    kind: FileKind,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    path: String? = null,
    lastModified: Long = 0L,
) {
    val cs = MaterialTheme.colorScheme
    val visual = kindVisual(kind)
    val thumbnail by rememberThumbnail(path, kind, lastModified)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) cs.primary else visual.container),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = cs.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            } else if (thumbnail != null) {
                val thumb = thumbnail
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                if (kind == FileKind.Video) {
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
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else if (kind == FileKind.Audio) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.content,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
