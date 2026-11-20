package cn.daqing.leap.a10energy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.daqing.leap.a10energy.ui.A10Theme
import cn.daqing.leap.a10energy.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            A10Theme {
                AppRoot()
            }
        }
    }
}
