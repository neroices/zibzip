package dev.neroices.zibzip.data

import android.content.Context
import dev.neroices.zibzip.domain.SortField
import dev.neroices.zibzip.domain.SortOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val showHidden: Boolean = false,
    val sort: SortOption = SortOption(),
    val useTrash: Boolean = false,
    val showParentFolder: Boolean = true,
    val keepScreenOn: Boolean = false,
)

/** Tiny SharedPreferences-backed store; avoids pulling in DataStore for five values. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("zibzip_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        prefs.edit()
            .putString(KEY_THEME, next.theme.name)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putBoolean(KEY_HIDDEN, next.showHidden)
            .putString(KEY_SORT_FIELD, next.sort.field.name)
            .putBoolean(KEY_SORT_ASC, next.sort.ascending)
            .putBoolean(KEY_FOLDERS_FIRST, next.sort.foldersFirst)
            .putBoolean(KEY_USE_TRASH, next.useTrash)
            .putBoolean(KEY_SHOW_PARENT_FOLDER, next.showParentFolder)
            .putBoolean(KEY_KEEP_SCREEN_ON, next.keepScreenOn)
            .apply()
    }

    private fun read(): AppSettings = AppSettings(
        theme = enumOr(prefs.getString(KEY_THEME, null), ThemeMode.System),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
        showHidden = prefs.getBoolean(KEY_HIDDEN, false),
        sort = SortOption(
            field = enumOr(prefs.getString(KEY_SORT_FIELD, null), SortField.Name),
            ascending = prefs.getBoolean(KEY_SORT_ASC, true),
            foldersFirst = prefs.getBoolean(KEY_FOLDERS_FIRST, true),
        ),
        useTrash = prefs.getBoolean(KEY_USE_TRASH, false),
        showParentFolder = prefs.getBoolean(KEY_SHOW_PARENT_FOLDER, true),
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_HIDDEN = "show_hidden"
        const val KEY_SORT_FIELD = "sort_field"
        const val KEY_SORT_ASC = "sort_asc"
        const val KEY_FOLDERS_FIRST = "folders_first"
        const val KEY_USE_TRASH = "use_trash"
        const val KEY_SHOW_PARENT_FOLDER = "show_parent_folder"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
