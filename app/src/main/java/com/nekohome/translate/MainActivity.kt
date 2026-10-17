package com.nekohome.translate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekohome.translate.ui.TranslateScreen
import com.nekohome.translate.ui.TranslateViewModel
import com.nekohome.translate.ui.theme.NekoTranslateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NekoTranslateTheme {
                val vm: TranslateViewModel = viewModel()
                TranslateScreen(vm)
            }
        }
    }
}
