package org.fossify.gallery.nas.smoke

import org.fossify.gallery.nas.NasCoreCases
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var failures = 0
    val output = StringBuilder()
    val cases = NasCoreCases.all()
    check(cases.map { it.name }.toSet().size == cases.size) { "Duplicate test case names" }
    cases.forEach { case ->
        try {
            case.run()
            println("PASS ${case.name}")
            output.append("<testcase name=\"${escape(case.name)}\"/>")
        } catch (error: Throwable) {
            failures++
            System.err.println("FAIL ${case.name}: $error")
            error.printStackTrace()
            output.append("<testcase name=\"${escape(case.name)}\">")
            output.append("<failure message=\"${escape(error.toString())}\"/></testcase>")
        }
    }
    val summary = "NAS core: ${cases.size - failures}/${cases.size} PASS; $failures FAIL " +
        "(standalone JVM, not Android/SMB)"
    println(summary)
    if (args.isNotEmpty()) {
        File(args[0]).writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<testsuite name=\"nas-core-standalone\" tests=\"${cases.size}\" failures=\"$failures\">" +
                "$output</testsuite>\n"
        )
    }
    if (failures != 0) exitProcess(1)
}

private fun escape(value: String): String = value.replace(
    "&",
    "&amp;"
).replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
