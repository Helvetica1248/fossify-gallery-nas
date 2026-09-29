package org.fossify.gallery.nas.next

fun main() {
    val cases = NasNextCases.all()
    for (case in cases) { case.run(); println("PASS ${case.name}") }
    println("NAS search/range core: ${cases.size}/${cases.size} PASS (standalone JVM, not Android/SMB)")
}
