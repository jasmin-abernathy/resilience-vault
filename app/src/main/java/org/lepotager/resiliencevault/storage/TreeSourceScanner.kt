package org.lepotager.resiliencevault.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class SourceEntry(
    val relativePath: String,
    val uri: Uri,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
)

class TreeSourceScanner(private val context: Context) {
    fun scan(rootUri: Uri): Sequence<SourceEntry> = sequence {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@sequence
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.add(root to "")
        while (stack.isNotEmpty()) {
            val (current, prefix) = stack.removeLast()
            for (child in current.listFiles()) {
                val safeName = child.name ?: continue
                val path = if (prefix.isEmpty()) safeName else "$prefix/$safeName"
                if (child.isDirectory) {
                    stack.add(child to path)
                } else if (child.isFile) {
                    yield(SourceEntry(path, child.uri, child.length(), child.lastModified()))
                }
            }
        }
    }
}
