package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.ZibzipException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOperationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ops = FileOperations()

    @Test
    fun copy_addsSuffixOnNameClash_andKeepsOriginals() {
        runBlocking {
            val src = tmp.newFile("a.txt").apply { writeText("x") }
            val dir = tmp.newFolder("d")
            File(dir, "a.txt").writeText("existing")

            val copies = ops.copy(listOf(src), dir)

            assertEquals("a (1).txt", copies.single().name)
            assertEquals("existing", File(dir, "a.txt").readText())
            assertEquals("x", File(dir, "a (1).txt").readText())
            assertTrue(src.exists())
        }
    }

    @Test
    fun copy_folderRecursively() {
        runBlocking {
            val src = tmp.newFolder("tree")
            File(src, "n/m").mkdirs()
            File(src, "n/m/f.txt").writeText("deep")
            val dest = tmp.newFolder("dest")

            ops.copy(listOf(src), dest)

            assertEquals("deep", File(dest, "tree/n/m/f.txt").readText())
        }
    }

    @Test
    fun copy_folderIntoItself_isRejected() {
        runBlocking {
            val src = tmp.newFolder("self")
            val inner = File(src, "inner").also { it.mkdirs() }
            try {
                ops.copy(listOf(src), inner)
                fail("expected Unsupported")
            } catch (e: ZibzipException.Unsupported) {
            }
        }
    }

    @Test
    fun move_renamesAndHandlesClash() {
        runBlocking {
            val src = tmp.newFile("m.txt").apply { writeText("moved") }
            val dest = tmp.newFolder("to")
            File(dest, "m.txt").writeText("blocker")

            assertEquals(1, ops.move(listOf(src), dest))

            assertFalse(src.exists())
            assertEquals("moved", File(dest, "m (1).txt").readText())
            assertEquals("blocker", File(dest, "m.txt").readText())
        }
    }

    @Test
    fun delete_removesTrees() {
        runBlocking {
            val dir = tmp.newFolder("gone")
            File(dir, "a/b").mkdirs()
            File(dir, "a/b/c.txt").writeText("c")

            ops.delete(listOf(dir))

            assertFalse(dir.exists())
        }
    }

    @Test
    fun rename_validatesNames() {
        val f = tmp.newFile("old.txt")
        tmp.newFile("taken.txt")
        for (bad in listOf("", "  ", "a/b", "..", ".")) {
            try {
                ops.rename(f, bad)
                fail("expected InvalidName for '$bad'")
            } catch (e: ZibzipException.InvalidName) {
            }
        }
        try {
            ops.rename(f, "taken.txt")
            fail("expected AlreadyExists")
        } catch (e: ZibzipException.AlreadyExists) {
        }
        assertEquals("new.txt", ops.rename(f, "new.txt").name)
    }

    @Test
    fun createFolder_rejectsDuplicates() {
        ops.createFolder(tmp.root, "docs")
        try {
            ops.createFolder(tmp.root, "docs")
            fail("expected AlreadyExists")
        } catch (e: ZibzipException.AlreadyExists) {
        }
    }

    @Test
    fun folderStats_countsRecursively() {
        runBlocking {
            val dir = tmp.newFolder("stats")
            File(dir, "x").mkdirs()
            File(dir, "a.bin").writeBytes(ByteArray(10))
            File(dir, "x/b.bin").writeBytes(ByteArray(20))
            val s = ops.folderStats(dir)
            assertEquals(30L, s.bytes)
            assertEquals(2, s.files)
            assertEquals(1, s.folders)
        }
    }

    @Test
    fun sha256_computesCorrectChecksum() = runBlocking {
        val file = tmp.newFile("sample.txt").apply { writeText("hello world\n") }
        val expected = "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447"
        val actual = ops.sha256(file)
        assertEquals(expected, actual)
    }

    @Test
    fun sha256_emptyFile() = runBlocking {
        val file = tmp.newFile("empty.txt")
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val actual = ops.sha256(file)
        assertEquals(expected, actual)
    }
}

