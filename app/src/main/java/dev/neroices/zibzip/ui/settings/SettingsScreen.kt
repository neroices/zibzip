package dev.neroices.zibzip.ui.settings

import android.os.Build
import dev.neroices.zibzip.BuildConfig
import dev.neroices.zibzip.data.CrashReportManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.neroices.zibzip.R
import dev.neroices.zibzip.domain.formatSize
import dev.neroices.zibzip.data.AppSettings
import dev.neroices.zibzip.data.ThemeMode
import dev.neroices.zibzip.domain.SortField
import dev.neroices.zibzip.ui.components.ConfirmDialog
import dev.neroices.zibzip.ui.files.FloatingBarClearance
import dev.neroices.zibzip.ui.files.LocalFloatingBarClearance
import dev.neroices.zibzip.ui.shareTextFile
import dev.neroices.zibzip.zibzipApp
import kotlinx.coroutines.launch

/** Open-source engine & library credits. */
data class Library(val name: String, val purpose: String, val license: String, val url: String)

val OpenSourceLibraries = listOf(
    Library(
        "zip (deflate-zlib-ng)",
        "ZIP compression, extraction, and AES encryption",
        "MIT",
        "https://github.com/zip-rs/zip2"
    ),
    Library(
        "sevenz-rust2",
        "7-Zip LZMA, LZMA2, and AES archive compression and decompression",
        "Apache-2.0",
        "https://github.com/hasenbanck/sevenz-rust2"
    ),
    Library(
        "rars",
        "RAR archive creation, extraction, and inspection",
        "Apache-2.0",
        "https://github.com/bitplane/rars"
    ),
    Library(
        "rayon",
        "Parallel multi-threaded file scanning and sorting",
        "MIT / Apache-2.0",
        "https://github.com/rayon-rs/rayon"
    ),
    Library(
        "flate2",
        "DEFLATE and GZIP compression and decompression",
        "MIT / Apache-2.0",
        "https://github.com/rust-lang/flate2-rs"
    ),
    Library(
        "zstd",
        "Zstandard compression and decompression codecs",
        "MIT / Apache-2.0",
        "https://github.com/gyscos/zstd-rs"
    ),
    Library(
        "tar",
        "TAR archive reading, creation, and extraction",
        "MIT / Apache-2.0",
        "https://github.com/composefs/tar-rs"
    ),
    Library(
        "bzip2 (libbz2-rs-sys)",
        "Pure-Rust BZIP2 compression and decompression",
        "MIT / Apache-2.0",
        "https://github.com/trifectatechfoundation/bzip2-rs"
    ),
    Library(
        "lzma-rust2",
        "Pure-Rust LZMA, LZMA2, and XZ streaming compression and decompression",
        "Apache-2.0",
        "https://github.com/hasenbanck/lzma-rust2"
    ),
    Library(
        "walkdir",
        "Fast recursive directory traversal for file scanning and search",
        "Unlicense / MIT",
        "https://github.com/BurntSushi/walkdir"
    ),
    Library(
        "chrono",
        "Date and time handling for entry modification timestamps",
        "MIT / Apache-2.0",
        "https://github.com/chronotope/chrono"
    ),
    Library(
        "jni",
        "Rust JNI bindings for Kotlin and Android runtime interoperability",
        "MIT / Apache-2.0",
        "https://github.com/jni-rs/jni-rs"
    ),
    Library(
        "Jetpack Compose",
        "Declarative UI toolkit for Android",
        "Apache-2.0",
        "https://developer.android.com/compose"
    ),
    Library(
        "Material 3",
        "Material Design 3 components and dynamic theming",
        "Apache-2.0",
        "https://m3.material.io"
    ),
    Library(
        "Kotlinx Coroutines",
        "Asynchronous programming and structured concurrency",
        "Apache-2.0",
        "https://github.com/Kotlin/kotlinx.coroutines"
    ),
    Library(
        "AndroidX",
        "Activity Compose, Core KTX, and Lifecycle architecture components",
        "Apache-2.0",
        "https://developer.android.com/jetpack/androidx"
    ),
    Library(
        "AndroidX Media3 (ExoPlayer)",
        "Video and media playback engine, UI components, and media session integration",
        "Apache-2.0",
        "https://developer.android.com/media/media3"
    ),
    Library(
        "Kotlinx Serialization",
        "Structured JSON serialization for trash metadata and state persistence",
        "Apache-2.0",
        "https://github.com/Kotlin/kotlinx.serialization"
    ),
    Library(
        "sora-editor",
        "A cool and optimized code editor",
        "LGPL-2.1",
        "https://github.com/Rosemoe/sora-editor"
    ),
)

enum class SettingsSubpage {
    Main,
    About,
    Libraries,
    Diagnostics,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var subpage by rememberSaveable { mutableStateOf(SettingsSubpage.Main) }

    BackHandler(enabled = subpage != SettingsSubpage.Main) {
        subpage = when (subpage) {
            SettingsSubpage.Libraries -> SettingsSubpage.About
            SettingsSubpage.Diagnostics -> SettingsSubpage.About
            SettingsSubpage.About -> SettingsSubpage.Main
            SettingsSubpage.Main -> SettingsSubpage.Main
        }
    }

    AnimatedContent(
        targetState = subpage,
        transitionSpec = {
            if (targetState.ordinal > initialState.ordinal) {
                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> -width } + fadeOut()
                )
            } else {
                (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> width } + fadeOut()
                )
            }
        },
        label = "settings_navigation",
        modifier = modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            SettingsSubpage.Main -> MainSettingsPage(
                settings = settings,
                onUpdate = onUpdate,
                onNavigateToAbout = { subpage = SettingsSubpage.About },
            )

            SettingsSubpage.About -> AboutDeveloperPage(
                onBack = { subpage = SettingsSubpage.Main },
                onNavigateToLibraries = { subpage = SettingsSubpage.Libraries },
                onNavigateToDiagnostics = { subpage = SettingsSubpage.Diagnostics },
            )

            SettingsSubpage.Libraries -> OpenSourceLibrariesPage(
                onBack = { subpage = SettingsSubpage.About },
            )

            SettingsSubpage.Diagnostics -> DiagnosticsPage(
                onBack = { subpage = SettingsSubpage.About },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainSettingsPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = cs.onSurface,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        SectionHeader(icon = Icons.Outlined.Palette, title = "Appearance")
        SettingsCard {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                val themeModes = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)
                themeModes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.theme == mode,
                        onClick = { onUpdate { it.copy(theme = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themeModes.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = cs.primaryContainer,
                            activeContentColor = cs.onPrimaryContainer,
                            inactiveContainerColor = cs.surfaceContainer,
                            inactiveContentColor = cs.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = if (mode == ThemeMode.System) "System" else mode.name,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                CardDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Palette,
                    title = "Dynamic color",
                    subtitle = "Use colors from your wallpaper",
                    checked = settings.dynamicColor,
                    onCheckedChange = { onUpdate { s -> s.copy(dynamicColor = it) } },
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }

        SectionHeader(icon = Icons.Outlined.Folder, title = "Files")
        SettingsCard {
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                val sortFields = SortField.entries
                sortFields.forEachIndexed { index, field ->
                    SegmentedButton(
                        selected = settings.sort.field == field,
                        onClick = { onUpdate { it.copy(sort = it.sort.copy(field = field)) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = sortFields.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = cs.primaryContainer,
                            activeContentColor = cs.onPrimaryContainer,
                            inactiveContainerColor = cs.surfaceContainer,
                            inactiveContentColor = cs.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = field.name,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            CardDivider()
            SettingsSwitchRow(
                icon = Icons.AutoMirrored.Outlined.Sort,
                title = "Ascending order",
                subtitle = null,
                checked = settings.sort.ascending,
                onCheckedChange = { onUpdate { s -> s.copy(sort = s.sort.copy(ascending = it)) } },
            )

            CardDivider()
            SettingsSwitchRow(
                icon = Icons.Outlined.Folder,
                title = "Folders first",
                subtitle = null,
                checked = settings.sort.foldersFirst,
                onCheckedChange = { onUpdate { s -> s.copy(sort = s.sort.copy(foldersFirst = it)) } },
            )

            CardDivider()
            SettingsSwitchRow(
                icon = Icons.Outlined.Visibility,
                title = "Show hidden files",
                subtitle = "Files and folders starting with a dot",
                checked = settings.showHidden,
                onCheckedChange = { onUpdate { s -> s.copy(showHidden = it) } },
            )

            CardDivider()
            SettingsSwitchRow(
                icon = Icons.Outlined.DriveFolderUpload,
                title = "Show \"..\" folder",
                subtitle = "Navigate up to parent directory",
                checked = settings.showParentFolder,
                onCheckedChange = { onUpdate { s -> s.copy(showParentFolder = it) } },
            )

            CardDivider()
            SettingsSwitchRow(
                icon = Icons.Outlined.Delete,
                title = "Trash",
                subtitle = "Move deleted files to trash before permanent deletion",
                checked = settings.useTrash,
                onCheckedChange = { onUpdate { s -> s.copy(useTrash = it) } },
            )

            if (settings.useTrash) {
                CardDivider()
                var showEmptyConfirm by remember { mutableStateOf(false) }
                var trashStats by remember { mutableStateOf<dev.neroices.zibzip.data.TrashStats?>(null) }
                LaunchedEffect(settings.useTrash) {
                    trashStats = context.zibzipApp.trash.getTrashStats()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showEmptyConfirm = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = null,
                        tint = cs.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(
                            text = if (trashStats != null && trashStats!!.totalBytes > 0) {
                                "Empty trash (${formatSize(trashStats!!.totalBytes)})"
                            } else {
                                "Empty trash"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurface,
                        )
                        Text(
                            text = when {
                                trashStats == null -> "Permanently remove all trashed items"
                                trashStats!!.count == 0 -> "Trash is empty"
                                else -> {
                                    val count = trashStats!!.count
                                    val unit = if (count == 1) "item" else "items"
                                    val bytes = formatSize(trashStats!!.totalBytes)
                                    "$count $unit · $bytes in trash"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }

                if (showEmptyConfirm) {
                    val sizeLabel = if (trashStats != null && trashStats!!.totalBytes > 0) " (${formatSize(trashStats!!.totalBytes)})" else ""
                    ConfirmDialog(
                        title = "Empty Trash?",
                        message = "All items$sizeLabel in Trash will be permanently deleted.\n\nThis cannot be undone.",
                        confirmLabel = "Empty",
                        onDismiss = { showEmptyConfirm = false },
                        onConfirm = {
                            showEmptyConfirm = false
                            val app = context.zibzipApp
                            val started = app.runner.start("Emptying trash", trackSpeed = false) { p ->
                                val count = app.trash.emptyTrash(p)
                                trashStats = app.trash.getTrashStats()
                                if (count > 0) "Emptied $count items from trash" else "Trash is already empty"
                            }
                            if (!started) app.toast("Too many operations running")
                        },
                        isTrash = true,
                    )
                }
            }
        }

        SectionHeader(icon = Icons.Outlined.Info, title = "About")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToAbout() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_app_logo),
                    contentDescription = "zibzip",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "zibzip",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} · Developer · Licenses",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(LocalFloatingBarClearance.current))
    }
}

@Composable
private fun AboutDeveloperPage(
    onBack: () -> Unit,
    onNavigateToLibraries: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top Navigation Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = cs.onSurface,
                )
            }
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // Hero Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = "zibzip",
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape),
            )
            Text(
                text = "zibzip",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = cs.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "An archive and file manager for Android, built with Jetpack Compose, Material 3, and a native Rust core.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(top = 8.dp),
            )
            Text(
                text = "Licensed under GPL-3.0",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { runCatching { uriHandler.openUri("https://www.gnu.org/licenses/gpl-3.0.html") } }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // Developer Section (email removed as requested)
        SectionHeader(icon = Icons.Outlined.Code, title = "Developer")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.avatar_neroices),
                    contentDescription = "neroices",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "neroices",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Developer & Maintainer",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            CardDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri("https://github.com/neroices") } }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "GitHub",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "@neroices",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            CardDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri("https://x.com/xeroices") } }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_x),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "X",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "@xeroices",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            CardDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri("https://neroices.github.io") } }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "Website",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Visit developer site",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Open Source Libraries Section
        SectionHeader(icon = Icons.Outlined.Description, title = "Open source libraries")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToLibraries() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "Open source libraries",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Third-party components & licenses",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Diagnostics Section
        SectionHeader(icon = Icons.Outlined.BugReport, title = "Diagnostics")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToDiagnostics() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "Diagnostics & logs",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Save logs, share diagnostics, or report issues",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(LocalFloatingBarClearance.current))
    }
}

@Composable
private fun DiagnosticsPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme

    val saveLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val report = CrashReportManager.buildFullDiagnosticReport()
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(report.toByteArray(Charsets.UTF_8))
                }
                context.zibzipApp.toast("Log saved successfully")
            }.onFailure {
                context.zibzipApp.toast("Failed to save log file")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top Navigation Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = cs.onSurface,
                )
            }
            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        SectionHeader(icon = Icons.Outlined.BugReport, title = "Logs")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { saveLogLauncher.launch(CrashReportManager.getLogFileName()) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "Save log to file",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Choose folder to save ${CrashReportManager.getLogFileName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            CardDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val report = CrashReportManager.buildFullDiagnosticReport()
                        context.shareTextFile(report, CrashReportManager.getLogFileName())
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "Share log",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Send log to other apps or nearby devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        SectionHeader(icon = Icons.Outlined.Info, title = "Support")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri("https://github.com/neroices/zibzip/issues/new") } }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = "Report an issue on GitHub",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                    )
                    Text(
                        text = "Open issue tracker in browser",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(LocalFloatingBarClearance.current))
    }
}

@Composable
private fun OpenSourceLibrariesPage(
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top Navigation Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = cs.onSurface,
                )
            }
            Text(
                text = "Open source libraries",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        OpenSourceLibraries.forEach { lib ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cs.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { runCatching { uriHandler.openUri(lib.url) } },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = lib.name,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = cs.surfaceContainerHigh,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                text = lib.license,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Text(
                        text = lib.purpose,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(LocalFloatingBarClearance.current))
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 6.dp, top = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
                uncheckedThumbColor = cs.outline,
                uncheckedTrackColor = cs.surfaceContainerHighest,
                uncheckedBorderColor = cs.outline,
            ),
        )
    }
}
