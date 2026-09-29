package org.fossify.gallery.nas.next

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class NasNextTest(private val case: NasNextCase) {
    @Test fun runCase() = case.run()
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> = NasNextCases.all().map { arrayOf<Any>(it) }
    }
}
