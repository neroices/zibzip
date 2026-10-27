package dev.neroices.zibzip.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.neroices.zibzip.domain.StorageVolumeInfo
import dev.neroices.zibzip.zibzipApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Modern folder picker dialog for selecting a destination directory.
 * Supports navigating folder hierarchy, switching storage volumes, and creating new folders.
 */
@Composable
fun FolderPickerDialog(
    initialDir: File? = null,
    onDismiss: () -> Unit,
    onConfirm: (File) -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { context.zibzipApp.storage }
    val primaryRoot = remember { storage.primaryRoot }
    val cs = MaterialTheme.colorScheme

    var volumes by remember { mutableStateOf<List<StorageVolumeInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        volumes = withContext(Dispatchers.IO) {
            storage.volumes(withStats = false)
        }
    }

    var currentDir by remember {
        mutableStateOf(initialDir?.takeIf { it.exists() && it.isDirectory } ?: primaryRoot)
    }

    var subfolders by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    LaunchedEffect(currentDir, reloadKey) {
        loading = true
        subfolders = withContext(Dispatchers.IO) {
            try {
                currentDir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != ".zibzip_trash" }
                    ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        loading = false
    }

    val settings by context.zibzipApp.settings.settings.collectAsStateWithLifecycle()
    val isAtVolumeRoot = remember(currentDir, volumes) {
        volumes.any { it.path == currentDir.path } || currentDir.parentFile == null
    }
    val canNavigateUp = !isAtVolumeRoot && currentDir.parentFile != null
    val showNavigateUp = settings.showParentFolder && canNavigateUp

    BackHandler(enabled = canNavigateUp) {
        currentDir.parentFile?.let { currentDir = it }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxDialogHeight = maxHeight
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 400.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight.coerceAtMost(600.dp))
                    .height(maxDialogHeight.coerceAtMost(520.dp)),
                shape = RoundedCornerShape(24.dp),
                color = cs.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                ) {
                // Header: Title + Up button + New Folder button + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(cs.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Select destination",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                            ),
                            color = cs.onSurface,
                        )
                        Text(
                            text = "Choose folder to extract to",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { showNewFolderDialog = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CreateNewFolder,
                            contentDescription = "New folder",
                            tint = cs.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                    color = cs.outlineVariant.copy(alpha = 0.35f),
                )

                // Volume Switcher Chips (when multiple storage volumes are present)
                if (volumes.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        volumes.forEach { volume ->
                            val selected = currentDir.path.startsWith(volume.path)
                            FilterChip(
                                selected = selected,
                                onClick = { currentDir = File(volume.path) },
                                label = { Text(volume.label) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (volume.isRemovable) Icons.Rounded.SdCard else Icons.Rounded.PhoneAndroid,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = cs.primaryContainer,
                                    selectedLabelColor = cs.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }

                // Current Directory Bar with Up Navigation
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cs.surfaceContainerHighest.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { currentDir.parentFile?.let { currentDir = it } },
                            enabled = canNavigateUp,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Up",
                                tint = if (canNavigateUp) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.38f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = currentDir.name.ifBlank { "Root" },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = cs.onSurface,
                                maxLines = 1,
                            )
                            Text(
                                text = currentDir.absolutePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Subfolders List
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when {
                        loading -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp,
                                    color = cs.primary,
                                )
                            }
                        }

                        subfolders.isEmpty() && !showNavigateUp -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FolderOpen,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(44.dp),
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "No subfolders",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Tap “Select this folder” below or create a new one.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }

                        else -> {
                            val folderListState = rememberLazyListState()
                            LazyColumn(
                                state = folderListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .mouseDragScroll(folderListState),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (showNavigateUp) {
                                    item(key = "..") {
                                        Surface(
                                            onClick = { currentDir.parentFile?.let { currentDir = it } },
                                            shape = RoundedCornerShape(12.dp),
                                            color = cs.surfaceContainerHigh,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Folder,
                                                    contentDescription = null,
                                                    tint = cs.primary,
                                                    modifier = Modifier.size(22.dp),
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "..",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = cs.onSurface,
                                                        maxLines = 1,
                                                    )
                                                    Text(
                                                        text = "Parent folder",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = cs.onSurfaceVariant,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (subfolders.isEmpty()) {
                                    item(key = "__empty__") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 32.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "No subfolders",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = cs.onSurfaceVariant,
                                            )
                                        }
                                    }
                                } else {
                                    items(subfolders, key = { it.absolutePath }) { folder ->
                                        Surface(
                                            onClick = { currentDir = folder },
                                            shape = RoundedCornerShape(12.dp),
                                            color = cs.surfaceContainerHigh,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Folder,
                                                    contentDescription = null,
                                                    tint = cs.primary,
                                                    modifier = Modifier.size(22.dp),
                                                )
                                                Spacer(Modifier.width(12.dp))
                                                Text(
                                                    text = folder.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = cs.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Icon(
                                                    imageVector = Icons.Rounded.ChevronRight,
                                                    contentDescription = null,
                                                    tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Bottom Action Buttons (Dual Pills or Stacked when space is tight or display scale is large)
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val fontScale = LocalDensity.current.fontScale
                    val shouldStack = maxWidth < 280.dp || fontScale > 1.15f

                    if (shouldStack) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { onConfirm(currentDir) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cs.primary,
                                    contentColor = cs.onPrimary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Select folder",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Button(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cs.surfaceContainerHighest,
                                    contentColor = cs.onSurface,
                                ),
                            ) {
                                Text(
                                    text = "Cancel",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cs.surfaceContainerHighest,
                                    contentColor = cs.onSurface,
                                ),
                            ) {
                                Text(
                                    text = "Cancel",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Button(
                                onClick = { onConfirm(currentDir) },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .fillMaxHeight()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = cs.primary,
                                    contentColor = cs.onPrimary,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Select folder",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

    if (showNewFolderDialog) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                val clean = name.trim()
                if (clean.isNotEmpty()) {
                    val newDir = File(currentDir, clean)
                    if (newDir.mkdir() || newDir.exists()) {
                        currentDir = newDir
                        reloadKey++
                    }
                }
                showNewFolderDialog = false
            },
        )
    }
}
