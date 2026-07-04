package com.insomnia.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.insomnia.app.navigation.InsomniaNavHost
import com.insomnia.app.ui.theme.InsomniaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InsomniaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InsomniaNavHost()
                }
            }
        }
    }
}
