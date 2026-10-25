package dev.neroices.zibzip.domain

enum class ArchiveFormat(
    val label: String,
    val suffixes: List<String>,
    /** The app can write this format. */
    val canCreate: Boolean,
    /** Can hold several files / folders (single-stream formats like .gz cannot). */
    val multiFile: Boolean,
    /** Password protection is supported when creating this format. */
    val passwordOnCreate: Boolean,
    /** A compression level makes sense for this format. */
    val hasLevel: Boolean,
) {
    ZIP("ZIP", listOf(".zip", ".jar", ".cbz"), true, true, true, true),
    SEVEN_Z("7z", listOf(".7z"), true, true, false, true),
    TAR("TAR", listOf(".tar"), true, true, false, false),
    TAR_GZ("TAR.GZ", listOf(".tar.gz", ".tgz"), true, true, false, true),
    TAR_BZ2("TAR.BZ2", listOf(".tar.bz2", ".tbz2", ".tbz"), true, true, false, true),
    TAR_XZ("TAR.XZ", listOf(".tar.xz", ".txz"), true, true, false, true),
    TAR_ZST("TAR.ZST", listOf(".tar.zst"), true, true, false, true),
    GZ("GZ", listOf(".gz"), true, false, false, true),
    BZ2("BZ2", listOf(".bz2"), true, false, false, true),
    XZ("XZ", listOf(".xz"), true, false, false, true),
    ZST("ZST", listOf(".zst"), true, false, false, true),
    RAR("RAR", listOf(".rar"), true, true, false, true);

    val primarySuffix: String get() = suffixes.first()

    /** Returns [name] without this format's suffix (case-insensitive). */
    fun stripSuffix(name: String): String {
        val lower = name.lowercase()
        val suffix = suffixes.filter { lower.endsWith(it) }.maxByOrNull { it.length } ?: return name
        return name.dropLast(suffix.length)
    }

    companion object {
        // Longest suffix first so ".tar.gz" wins over ".gz".
        private val bySuffix: List<Pair<String, ArchiveFormat>> =
            entries.flatMap { f -> f.suffixes.map { it to f } }.sortedByDescending { it.first.length }

        fun detect(fileName: String): ArchiveFormat? {
            val lower = fileName.lowercase()
            return bySuffix.firstOrNull { lower.endsWith(it.first) && lower.length > it.first.length }?.second
        }

        val creatable: List<ArchiveFormat> get() = entries.filter { it.canCreate }
    }
}
