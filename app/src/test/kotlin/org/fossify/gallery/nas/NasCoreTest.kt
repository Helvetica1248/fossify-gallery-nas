package org.fossify.gallery.nas

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class NasCoreTest(private val case: NasCoreCase) {
    @Test
    fun runCase() = case.run()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> = NasCoreCases.all().map { arrayOf<Any>(it) }
    }
}
