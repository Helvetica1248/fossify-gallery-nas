package org.fossify.gallery.nas.model

/** Links and DFS referrals must be rejected by a transport before producing these entries. */
enum class NasEntryKind { FILE, DIRECTORY }

data class NasRemoteKey(val source: NasSourceKey, val path: NasRelativePath) {
    init {
        require(!path.isRoot) { "A NAS entry cannot be the source root" }
    }
}

data class NasEntry(
    val key: NasRemoteKey,
    val kind: NasEntryKind,
    val size: Long?,
    val modifiedEpochMillis: Long?,
    val fileId: String? = null
) {
    init {
        require(size == null || size >= 0) { "Invalid NAS size" }
        require(modifiedEpochMillis == null || modifiedEpochMillis >= 0) { "Invalid NAS modification time" }
        require(fileId == null || fileId.length in 1..512) { "Invalid NAS file identifier" }
        require(fileId == null || fileId.none { Character.isSurrogate(it) }) { "Invalid NAS file identifier" }
    }

    val name: String get() = key.path.name
    val hasVersionMetadata: Boolean get() = size != null && modifiedEpochMillis != null
}
