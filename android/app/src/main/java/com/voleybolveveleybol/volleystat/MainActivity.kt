package com.voleybolveveleybol.volleystat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.voleybolveveleybol.volleystat.ui.theme.VolleyStatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent system bars with icon colours that follow the light or dark theme.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { VolleyStatTheme { VolleyStatApp() } }
    }
}
