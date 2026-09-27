package org.fossify.gallery.nas.model

/** A share-relative SMB path. This is not a URI or a local filesystem path. */
class NasRelativePath private constructor(val value: String) {
    val isRoot: Boolean get() = value.isEmpty()
    val name: String get() = value.substringAfterLast('/')
    val parent: NasRelativePath?
        get() = when {
            isRoot -> null
            '/' !in value -> ROOT
            else -> parse(value.substringBeforeLast('/'))
        }

    fun child(name: String): NasRelativePath {
        validateSegment(name)
        return parse(if (isRoot) name else "$value/$name")
    }

    fun resolve(relative: NasRelativePath): NasRelativePath = when {
        relative.isRoot -> this
        isRoot -> relative
        else -> parse("$value/${relative.value}")
    }

    override fun equals(other: Any?) = other is NasRelativePath && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = value

    companion object {
        val ROOT = NasRelativePath("")
        private const val MAX_PATH_UNITS = 32760
        private const val MAX_SEGMENT_UNITS = 255

        /** Do not trim, case-fold, URL-decode, or Unicode-normalize remote filenames. */
        fun parse(value: String): NasRelativePath {
            require(value.length <= MAX_PATH_UNITS) { "NAS path is too long" }
            if (value.isEmpty()) return ROOT
            value.split('/').forEach(::validateSegment)
            return NasRelativePath(value)
        }

        private fun validateSegment(segment: String) {
            require(segment.isNotEmpty() && segment.length <= MAX_SEGMENT_UNITS) { "Invalid NAS path segment" }
            require(segment != "." && segment != "..") { "Relative traversal is not allowed" }
            require(!segment.endsWith('.') && !segment.endsWith(' ')) { "Ambiguous NAS path segment" }
            require(segment.none {
                it == '/' || it == '\\' || it in ":*?\"<>|" || it.code < 32 || it.code in 127..159
            }) {
                "Invalid NAS path character"
            }
            var index = 0
            while (index < segment.length) {
                val current = segment[index]
                if (Character.isHighSurrogate(current)) {
                    require(index + 1 < segment.length && Character.isLowSurrogate(segment[index + 1])) {
                        "Invalid Unicode in NAS path"
                    }
                    index += 2
                } else {
                    require(!Character.isLowSurrogate(current)) { "Invalid Unicode in NAS path" }
                    index++
                }
            }
        }
    }
}
