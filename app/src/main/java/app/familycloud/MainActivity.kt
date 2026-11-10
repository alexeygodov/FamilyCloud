package app.familycloud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.familycloud.ui.FamilyCloudNav
import app.familycloud.ui.theme.FamilyCloudTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FamilyCloudApp
        setContent {
            FamilyCloudTheme {
                FamilyCloudNav(app)
            }
        }
    }
}
