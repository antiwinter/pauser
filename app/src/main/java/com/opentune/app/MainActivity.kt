package com.opentune.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.opentune.app.navigation.OpenTuneNavHost
import com.opentune.app.ui.theme.OpenTuneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenTuneTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OpenTuneNavHost()
                }
            }
        }
    }
}
