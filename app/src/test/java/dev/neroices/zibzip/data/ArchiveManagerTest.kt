package dev.neroices.zibzip.data

import dev.neroices.zibzip.domain.ArchiveFormat
import dev.neroices.zibzip.domain.ConflictPolicy
import dev.neroices.zibzip.domain.ZibzipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mgr = ArchiveManager()

    private fun sampleTree(): File {
        val root = tmp.newFolder("src")
        File(root, "a.txt").writeText("hello")
        File(root, "sub").mkdirs()
        File(root, "sub/b.bin").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        File(root, "sub/empty.txt").writeText("")
        return root
    }

    @Test
    fun roundTrip_everyCreatableMultiFileFormat() {
        runBlocking {
            val src = sampleTree()
            for (format in ArchiveFormat.creatable.filter { it.multiFile }) {
                val out = File(tmp.root, "out${format.primarySuffix}")
                mgr.create(listOf(src), out, format, level = 3)
                val entries = mgr.list(out)
                assertTrue("${format.label}: a.txt listed", entries.any { it.path == "src/a.txt" })

                val dest = tmp.newFolder("x-${format.name}")
                val n = mgr.extract(out, dest)
                assertEquals("${format.label}: files extracted", 3, n)
                assertEquals("hello", File(dest, "src/a.txt").readText())
                assertArrayEquals(
                    File(src, "sub/b.bin").readBytes(),
                    File(dest, "src/sub/b.bin").readBytes(),
                )
                assertEquals(0L, File(dest, "src/sub/empty.txt").length())
            }
        }
    }

    @Test
    fun roundTrip_singleStreamFormats() {
        runBlocking {
            val src = tmp.newFile("note.txt").apply { writeText("single stream payload") }
            for (format in listOf(ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ)) {
                val out = File(tmp.root, "note.txt${format.primarySuffix}")
                mgr.create(listOf(src), out, format)
                assertEquals(listOf("note.txt"), mgr.list(out).map { it.path })
                val dest = tmp.newFolder("s-${format.name}")
                val n = mgr.extract(out, dest)
                assertEquals(1, n)
                assertEquals("single stream payload", File(dest, "note.txt").readText())
            }
        }
    }

    @Test
    fun singleStreamFormats_rejectFolders() {
        runBlocking {
            val src = sampleTree()
            try {
                mgr.create(listOf(src), File(tmp.root, "x.gz"), ArchiveFormat.GZ)
                fail("expected Unsupported")
            } catch (e: ZibzipException.Unsupported) {
                // expected
            }
        }
    }

    @Test
    fun zip_passwordFlow() {
        runBlocking {
            val src = sampleTree()
            val out = File(tmp.root, "locked.zip")
            mgr.create(listOf(src), out, ArchiveFormat.ZIP, password = "s3cret")

            assertTrue(mgr.list(out).any { it.isEncrypted })

            try {
                mgr.extract(out, tmp.newFolder("no-pw"))
                fail("expected PasswordRequired")
            } catch (e: ZibzipException.PasswordRequired) {
            }
            try {
                mgr.extract(out, tmp.newFolder("bad-pw"), password = "nope")
                fail("expected WrongPassword")
            } catch (e: ZibzipException.WrongPassword) {
            }
            val dest = tmp.newFolder("good-pw")
            assertEquals(3, mgr.extract(out, dest, password = "s3cret"))
            assertEquals("hello", File(dest, "src/a.txt").readText())
        }
    }

    @Test
    fun selection_extractsOnlyChosenEntries_relativeToBase() {
        runBlocking {
            val src = sampleTree()
            val out = File(tmp.root, "sel.zip")
            mgr.create(listOf(src), out, ArchiveFormat.ZIP)
            val dest = tmp.newFolder("sel")
            // Select the "sub" folder while browsing inside "src".
            val n = mgr.extract(out, dest, selection = setOf("src/sub"), basePath = "src")
            assertEquals(2, n)
            assertTrue(File(dest, "sub/b.bin").exists())
            assertFalse(File(dest, "a.txt").exists())
        }
    }

    @Test
    fun conflicts_followPolicy() {
        runBlocking {
            val src = tmp.newFile("c.txt").apply { writeText("new") }
            val out = File(tmp.root, "c.zip")
            mgr.create(listOf(src), out, ArchiveFormat.ZIP)
            val dest = tmp.newFolder("conf")
            File(dest, "c.txt").writeText("old")

            mgr.extract(out, dest, conflict = ConflictPolicy.Skip)
            assertEquals("old", File(dest, "c.txt").readText())

            mgr.extract(out, dest, conflict = ConflictPolicy.KeepBoth)
            assertEquals("old", File(dest, "c.txt").readText())
            assertEquals("new", File(dest, "c (1).txt").readText())

            mgr.extract(out, dest, conflict = ConflictPolicy.Overwrite)
            assertEquals("new", File(dest, "c.txt").readText())
        }
    }

    @Test
    fun zipSlip_isRejected() {
        runBlocking {
            val evil = File(tmp.root, "evil.zip")
            ZipOutputStream(evil.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("../escaped.txt"))
                z.write("gotcha".toByteArray())
                z.closeEntry()
            }
            val dest = tmp.newFolder("jail")
            try {
                mgr.extract(evil, dest)
                fail("expected Unsupported")
            } catch (e: ZibzipException.Unsupported) {
            }
            assertFalse(File(tmp.root, "escaped.txt").exists())
        }
    }

    @Test
    fun corruptedArchives_reportCorruption() {
        runBlocking {
            val junk = ByteArray(2048).also { Random(7).nextBytes(it) }
            for (name in listOf("bad.zip", "bad.7z", "bad.tar.gz", "bad.tar.xz", "bad.rar")) {
                val f = File(tmp.root, name).apply { writeBytes(junk) }
                try {
                    mgr.list(f)
                    fail("$name: expected failure")
                } catch (e: ZibzipException.CorruptedArchive) {
                    // expected
                } catch (e: ZibzipException.PasswordRequired) {
                    fail("$name: unexpected PasswordRequired")
                }
            }
        }
    }

    @Test
    fun missingArchive_isNotFound() {
        runBlocking {
            try {
                mgr.list(File(tmp.root, "ghost.zip"))
                fail("expected NotFound")
            } catch (e: ZibzipException.NotFound) {
            }
        }
    }

    @Test
    fun cancellation_leavesNoPartialOutput() {
        runBlocking {
            val big = tmp.newFile("big.bin").apply { writeBytes(ByteArray(4_000_000).also { Random(1).nextBytes(it) }) }
            val out = File(tmp.root, "c.zip")
            lateinit var job: Job
            job = launch {
                try {
                    mgr.create(listOf(big), out, ArchiveFormat.ZIP) { done, _, _ -> if (done > 0) job.cancel() }
                    fail("should have been cancelled")
                } catch (e: CancellationException) {
                    // expected
                }
            }
            job.join()
            assertFalse(out.exists())
            assertFalse(File(tmp.root, "c.zip.part").exists())
        }
    }

    @Test
    fun progress_reachesTotal() {
        runBlocking {
            val src = sampleTree()
            var lastDone = 0L
            var lastTotal = 0L
            val out = File(tmp.root, "p.zip")
            mgr.create(listOf(src), out, ArchiveFormat.ZIP) { d, t, _ -> lastDone = d; lastTotal = t }
            assertEquals(300_005L, lastTotal)
            assertEquals(lastTotal, lastDone)
        }
    }

    @Test
    fun extractCancellation_cancelsCleanly() {
        runBlocking {
            val big = tmp.newFile("big_extract.bin").apply { writeBytes(ByteArray(4_000_000).also { Random(2).nextBytes(it) }) }
            val archive = File(tmp.root, "extract_cancel.zip")
            mgr.create(listOf(big), archive, ArchiveFormat.ZIP)
            val dest = tmp.newFolder("dest_cancel")

            lateinit var job: Job
            job = launch {
                try {
                    mgr.extract(archive, dest) { done, _, _ -> if (done > 0) job.cancel() }
                    fail("should have been cancelled")
                } catch (e: CancellationException) {
                    // expected
                }
            }
            job.join()
        }
    }
}
