package dev.neroices.zibzip.ui.editor

import androidx.compose.runtime.Immutable

@Immutable
data class TextEditorArgs(
    val path: String,
    val isReadOnly: Boolean = false,
)
