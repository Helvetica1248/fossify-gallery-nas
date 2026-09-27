package org.fossify.gallery.nas.next

import org.fossify.gallery.nas.smb.SmbSafety
import org.fossify.gallery.nas.smb.SMB_TIMEOUT_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.SocketFactory

class NasExternalTimeoutTest {
    @Test fun externalIdleDoesNotRelaxCommandTimeoutOrSigning() {
        val config = SmbSafety.config(SocketFactory.getDefault(), EXTERNAL_IDLE)
        assertEquals(EXTERNAL_IDLE, config.soTimeout)
        assertEquals(SMB_TIMEOUT_MILLIS.toLong(), config.transactTimeout)
        assertTrue(config.isSigningRequired)
        assertEquals(SMB_TIMEOUT_MILLIS, SmbSafety.config(SocketFactory.getDefault()).soTimeout)
    }

    private companion object { const val EXTERNAL_IDLE = 11 * 60 * 1000 }
}
