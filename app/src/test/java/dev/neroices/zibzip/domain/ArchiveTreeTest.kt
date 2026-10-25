package dev.neroices.zibzip.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTreeTest {

    private fun file(path: String, size: Long = 10) = ArchiveEntryItem(path, false, size, size / 2, 0)
    private fun dir(path: String) = ArchiveEntryItem(path, true, 0, 0, 0)

    @Test
    fun synthesisesMissingParentFolders() {
        val tree = ArchiveTree(listOf(file("a/b/c.txt"), file("a/d.txt"), file("root.txt")))
        assertEquals(listOf("a", "root.txt"), tree.childrenOf("").map { it.path })
        assertEquals(listOf("a/b", "a/d.txt"), tree.childrenOf("a").map { it.path })
        assertEquals(listOf("a/b/c.txt"), tree.childrenOf("a/b").map { it.path })
        assertTrue(tree.childrenOf("a").first().isDirectory)
    }

    @Test
    fun foldersSortBeforeFilesAndNamesAreNatural() {
        val tree = ArchiveTree(listOf(file("f10.txt"), file("f2.txt"), dir("zeta"), dir("alpha")))
        assertEquals(listOf("alpha", "zeta", "f2.txt", "f10.txt"), tree.childrenOf("").map { it.name })
    }

    @Test
    fun trailingSlashesAreNormalised() {
        val tree = ArchiveTree(listOf(dir("docs/"), file("docs/readme.md")))
        assertEquals(listOf("docs"), tree.childrenOf("/").map { it.path })
        assertEquals(listOf("docs/readme.md"), tree.childrenOf("docs/").map { it.path })
    }

    @Test
    fun countsOnlyFiles() {
        val tree = ArchiveTree(listOf(dir("d"), file("d/a", 100), file("d/b", 50)))
        assertEquals(2, tree.fileCount)
        assertEquals(150L, tree.totalSize)
    }

    @Test
    fun unknownFolderIsEmpty() {
        val tree = ArchiveTree(listOf(file("a.txt")))
        assertTrue(tree.childrenOf("nope").isEmpty())
        assertTrue(!tree.hasFolder("nope"))
        assertTrue(tree.hasFolder(""))
    }

    @Test
    fun parentOf_returnsParentDirectory() {
        assertEquals("docs/guides", ArchiveTree.parentOf("docs/guides/readme.md"))
        assertEquals("docs", ArchiveTree.parentOf("docs/guides"))
        assertEquals("", ArchiveTree.parentOf("docs"))
        assertEquals("", ArchiveTree.parentOf(""))
    }
}
