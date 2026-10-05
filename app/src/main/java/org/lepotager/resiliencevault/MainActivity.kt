package org.lepotager.resiliencevault

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import org.lepotager.resiliencevault.settings.VaultSettings
import org.lepotager.resiliencevault.ui.ResilienceVaultApp

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ResilienceVaultApp(VaultSettings(applicationContext)) }
    }
}
