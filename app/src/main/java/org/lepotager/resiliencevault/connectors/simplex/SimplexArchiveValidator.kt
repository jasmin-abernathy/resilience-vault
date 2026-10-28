package org.lepotager.resiliencevault.connectors.simplex

/**
 * A minimal opaque-archive check. A ZIP signature DOES NOT prove the archive
 * originated from SimpleX, is complete, is encrypted, or can be restored.
 * Never unpack untrusted archives in the connector.
 */
enum class SimplexArchiveIssue {
    NAME_NOT_ZIP,
    EMPTY_OR_TRUNCATED,
    INVALID_ZIP_HEADER
}

object SimplexArchiveValidator {
    fun check(
        displayName: String?,
        declaredSize: Long?,
        firstBytes: ByteArray
    ): SimplexArchiveIssue? {
        if (displayName == null || !displayName.endsWith(".zip", ignoreCase = true)) {
            return SimplexArchiveIssue.NAME_NOT_ZIP
        }
        if ((declaredSize != null && declaredSize <= 4L) || firstBytes.size < 4) {
            return SimplexArchiveIssue.EMPTY_OR_TRUNCATED
        }
        // ZIP local file header. Empty ZIPs and trailing-data descriptors are not a useful export.
        if (firstBytes[0] != 0x50.toByte() ||
            firstBytes[1] != 0x4b.toByte() ||
            firstBytes[2] != 0x03.toByte() ||
            firstBytes[3] != 0x04.toByte()
        ) {
            return SimplexArchiveIssue.INVALID_ZIP_HEADER
        }
        return null
    }
}
