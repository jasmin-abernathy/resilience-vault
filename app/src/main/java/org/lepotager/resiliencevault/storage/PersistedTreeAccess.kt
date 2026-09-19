package org.lepotager.resiliencevault.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

object PersistedTreeAccess {
    fun persistReadAccess(contentResolver: ContentResolver, uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun hasReadAccess(contentResolver: ContentResolver, uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
