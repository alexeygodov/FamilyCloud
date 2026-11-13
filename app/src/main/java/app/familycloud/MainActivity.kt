package app.familycloud

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.familycloud.ui.FamilyCloudNav
import app.familycloud.ui.theme.FamilyCloudTheme

class MainActivity : ComponentActivity() {
    private val openRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRoute.value = intent?.getStringExtra(EXTRA_OPEN)
        val app = application as FamilyCloudApp
        setContent {
            FamilyCloudTheme {
                FamilyCloudNav(app, openRoute.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRoute.value = intent.getStringExtra(EXTRA_OPEN)
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
