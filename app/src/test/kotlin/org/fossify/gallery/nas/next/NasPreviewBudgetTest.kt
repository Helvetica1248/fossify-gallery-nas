package org.fossify.gallery.nas.next

import org.fossify.gallery.nas.external.NasPreviewBudget
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class NasPreviewBudgetTest {
    @Test fun repeatedReadsConsumeTheSameByteBudget() {
        val budget = NasPreviewBudget(maxBytes = 4)
        budget.charge(2)
        budget.charge(2)
        assertThrows(IOException::class.java) { budget.charge(1) }
    }

    @Test fun deadlineAlsoBoundsMetadataAndQueueWait() {
        var now = 0L
        val budget = NasPreviewBudget(timeoutMillis = 2, now = { now })
        budget.charge(0)
        now = 2
        assertThrows(IOException::class.java) { budget.charge(0) }
    }
}
