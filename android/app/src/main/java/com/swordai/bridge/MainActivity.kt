package com.swordai.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.swordai.bridge.ui.AppRoot
import com.swordai.bridge.ui.theme.SwordTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwordTheme {
                AppRoot()
            }
        }
    }
}
