package dev.neroices.zibzip.domain

import androidx.compose.runtime.Immutable

/** Items placed on the app clipboard by Copy / Move, waiting for a Paste. */
@Immutable
data class FileClipboard(val paths: List<String>, val cut: Boolean)

/**
 * Folder view over the flat entry list an archive reports.
 *
 * Archives often omit explicit directory entries (`a/b/c.txt` without `a/` or `a/b/`), so missing
 * ancestors are synthesised. Built once per archive; [childrenOf] is then a map lookup, which
 * keeps navigation inside huge archives instant.
 */
@Immutable
class ArchiveTree(entries: List<ArchiveEntryItem>) {

    private val children: Map<String, List<ArchiveEntryItem>>

    val fileCount: Int
    val totalSize: Long

    init {
        val dirs = HashMap<String, ArchiveEntryItem>()
        val files = ArrayList<ArchiveEntryItem>(entries.size)

        fun ensureDir(path: String) {
            var p = path
            while (p.isNotEmpty() && p !in dirs) {
                dirs[p] = ArchiveEntryItem(p, true, 0, 0, 0)
                p = parentOf(p)
            }
        }

        for (e in entries) {
            val p = e.path.trim('/')
            if (p.isEmpty()) continue
            if (e.isDirectory) {
                dirs[p] = e.copy(path = p) // a real entry wins over a synthesised one
                ensureDir(parentOf(p))
            } else {
                files += e.copy(path = p)
                ensureDir(parentOf(p))
            }
        }

        val order = Comparator<ArchiveEntryItem> { a, b ->
            if (a.isDirectory != b.isDirectory) if (a.isDirectory) -1 else 1
            else NaturalOrder.compare(a.name, b.name)
        }
        children = (dirs.values.asSequence() + files.asSequence())
            .groupBy { parentOf(it.path) }
            .mapValues { (_, list) -> list.sortedWith(order) }
        fileCount = files.size
        totalSize = files.sumOf { it.size.coerceAtLeast(0) }
    }

    fun childrenOf(dir: String): List<ArchiveEntryItem> = children[dir.trim('/')] ?: emptyList()

    fun hasFolder(dir: String): Boolean = dir.isEmpty() || children.containsKey(dir.trim('/'))

    companion object {
        fun parentOf(path: String): String = path.substringBeforeLast('/', "")
    }
}
