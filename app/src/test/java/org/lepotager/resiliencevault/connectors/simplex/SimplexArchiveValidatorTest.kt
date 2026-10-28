package org.lepotager.resiliencevault.connectors.simplex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimplexArchiveValidatorTest {
    private val zipHeader = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

    @Test fun acceptsOnlyAnOpaqueZipCandidate() {
        assertNull(SimplexArchiveValidator.check("simplex.zip", 1024L, zipHeader))
        assertNull(SimplexArchiveValidator.check("SIMPLEX.ZIP", null, zipHeader))
    }

    @Test fun rejectsUnexpectedExtensions() {
        assertEquals(SimplexArchiveIssue.NAME_NOT_ZIP,
            SimplexArchiveValidator.check("messages.json", 1024L, zipHeader))
        assertEquals(SimplexArchiveIssue.NAME_NOT_ZIP,
            SimplexArchiveValidator.check(null, null, zipHeader))
    }

    @Test fun rejectsEmptyOrTruncatedArchives() {
        assertEquals(SimplexArchiveIssue.EMPTY_OR_TRUNCATED,
            SimplexArchiveValidator.check("export.zip", 3L, zipHeader))
        assertEquals(SimplexArchiveIssue.EMPTY_OR_TRUNCATED,
            SimplexArchiveValidator.check("export.zip", null, byteArrayOf(0x50, 0x4b)))
    }

    @Test fun rejectsFakeZipAndEmptyZipSignature() {
        assertEquals(SimplexArchiveIssue.INVALID_ZIP_HEADER,
            SimplexArchiveValidator.check("export.zip", 999L, byteArrayOf(0x50, 0x4b, 0x05, 0x06)))
        assertEquals(SimplexArchiveIssue.INVALID_ZIP_HEADER,
            SimplexArchiveValidator.check("export.zip", 999L, byteArrayOf(1, 2, 3, 4)))
    }
}
