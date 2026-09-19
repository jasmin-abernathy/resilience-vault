package org.lepotager.resiliencevault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.lepotager.resiliencevault.settings.VaultSettings
import org.lepotager.resiliencevault.ui.ResilienceVaultApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ResilienceVaultApp(VaultSettings(applicationContext)) }
    }
}
