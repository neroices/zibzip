package dev.neroices.zibzip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.neroices.zibzip.ui.ZibzipRoot
import dev.neroices.zibzip.ui.theme.ZibzipTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by zibzipApp.settings.settings.collectAsStateWithLifecycle()
            ZibzipTheme(settings) { ZibzipRoot() }
        }
    }
}
