package org.lepotager.resiliencevault.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/** Read-only access to a document explicitly picked through ACTION_OPEN_DOCUMENT. */
object PersistedDocumentAccess {
    fun persistReadAccess(resolver: ContentResolver, uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun hasReadAccess(resolver: ContentResolver, uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun releaseReadAccess(resolver: ContentResolver, uri: Uri) {
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
