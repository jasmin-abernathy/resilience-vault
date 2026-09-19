package org.lepotager.resiliencevault.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vaultDataStore by preferencesDataStore(name = "vault_settings")

data class VaultSettingsSnapshot(
    val signalTreeUri: Uri? = null,
    val genericTreeUri: Uri? = null,
    val telegramEnabled: Boolean = false
)

class VaultSettings(private val context: Context) {
    private object Keys {
        val signalTree = stringPreferencesKey("signal_tree_uri")
        val genericTree = stringPreferencesKey("generic_tree_uri")
        val telegramEnabled = booleanPreferencesKey("telegram_enabled")
    }

    val snapshot: Flow<VaultSettingsSnapshot> = context.vaultDataStore.data.map { prefs ->
        VaultSettingsSnapshot(
            signalTreeUri = prefs.uriOrNull(Keys.signalTree),
            genericTreeUri = prefs.uriOrNull(Keys.genericTree),
            telegramEnabled = prefs[Keys.telegramEnabled] ?: false
        )
    }

    suspend fun setSignalTree(uri: Uri?) = context.vaultDataStore.edit {
        it.putOrRemove(Keys.signalTree, uri)
    }

    suspend fun setGenericTree(uri: Uri?) = context.vaultDataStore.edit {
        it.putOrRemove(Keys.genericTree, uri)
    }

    suspend fun setTelegramEnabled(enabled: Boolean) = context.vaultDataStore.edit {
        it[Keys.telegramEnabled] = enabled
    }
}

private fun Preferences.uriOrNull(key: Preferences.Key<String>): Uri? =
    this[key]?.let(Uri::parse)

private fun androidx.datastore.preferences.core.MutablePreferences.putOrRemove(
    key: Preferences.Key<String>,
    uri: Uri?
) {
    if (uri == null) remove(key) else this[key] = uri.toString()
}
