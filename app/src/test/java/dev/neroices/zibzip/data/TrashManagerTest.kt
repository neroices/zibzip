package dev.neroices.zibzip.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrashManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ops = FileOperations()
    private val trashManager by lazy {
        TrashManager(
            getRoots = { listOf(tmp.root) },
            fileOps = ops,
        )
    }

    @Test
    fun moveToTrash_movesFile_createsMetadataAndNomedia() = runBlocking {
        val testFile = tmp.newFile("sample.txt").apply { writeText("hello world") }
        val moved = trashManager.moveToTrash(listOf(testFile))

        assertEquals(1, moved)
        assertFalse(testFile.exists())

        val trashDir = File(tmp.root, TrashManager.TRASH_DIR_NAME)
        assertTrue(trashDir.exists())
        assertTrue(File(trashDir, TrashManager.NOMEDIA_FILE_NAME).exists())

        val items = trashManager.listTrash()
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("sample.txt", item.originalName)
        assertEquals(testFile.absolutePath, item.originalPath)
        assertEquals(11L, item.size)
        assertFalse(item.isDirectory)
        assertTrue(item.trashFile.exists())
        assertEquals("hello world", item.trashFile.readText())
    }

    @Test
    fun restore_restoresToOriginalLocation() = runBlocking {
        val folder = tmp.newFolder("documents")
        val doc = File(folder, "notes.md").apply { writeText("meeting notes") }

        trashManager.moveToTrash(listOf(doc))
        assertFalse(doc.exists())

        val items = trashManager.listTrash()
        assertEquals(1, items.size)

        val restored = trashManager.restore(items.first())
        assertEquals("notes.md", restored.name)
        assertTrue(restored.exists())
        assertEquals("meeting notes", restored.readText())
        assertEquals(doc.absolutePath, restored.absolutePath)

        // Trash should now be empty
        assertEquals(0, trashManager.listTrash().size)
    }

    @Test
    fun restore_handlesNameCollision_byGeneratingUniqueName() = runBlocking {
        val file = tmp.newFile("report.pdf").apply { writeText("original") }
        trashManager.moveToTrash(listOf(file))

        // Recreate file with same name at original path
        file.writeText("new report")

        val items = trashManager.listTrash()
        val restored = trashManager.restore(items.first())

        assertTrue(restored.exists())
        assertEquals("report (1).pdf", restored.name)
        assertEquals("original", restored.readText())
        assertEquals("new report", file.readText())
    }

    @Test
    fun restore_recreatesParentDirectoryIfDeleted() = runBlocking {
        val parent = tmp.newFolder("nested", "deep")
        val file = File(parent, "data.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        trashManager.moveToTrash(listOf(file))

        // Delete parent directory completely
        parent.delete()
        assertFalse(parent.exists())

        val items = trashManager.listTrash()
        val restored = trashManager.restore(items.first())

        assertTrue(parent.exists())
        assertTrue(restored.exists())
        assertEquals(file.absolutePath, restored.absolutePath)
    }

    @Test
    fun deletePermanently_removesTrashFileAndMetadata() = runBlocking {
        val file = tmp.newFile("to_delete.txt").apply { writeText("delete me") }
        trashManager.moveToTrash(listOf(file))

        val items = trashManager.listTrash()
        assertEquals(1, items.size)

        val success = trashManager.deletePermanently(items.first())
        assertTrue(success)
        assertEquals(0, trashManager.listTrash().size)
    }

    @Test
    fun emptyTrash_removesAllItems() = runBlocking {
        val f1 = tmp.newFile("f1.txt").apply { writeText("1") }
        val f2 = tmp.newFile("f2.txt").apply { writeText("2") }
        val folder = tmp.newFolder("subfolder").apply {
            File(this, "inner.txt").writeText("inner")
        }

        trashManager.moveToTrash(listOf(f1, f2, folder))
        assertEquals(3, trashManager.listTrash().size)

        val emptied = trashManager.emptyTrash()
        assertEquals(3, emptied)
        assertEquals(0, trashManager.listTrash().size)

        val trashDir = File(tmp.root, TrashManager.TRASH_DIR_NAME)
        assertTrue(File(trashDir, TrashManager.NOMEDIA_FILE_NAME).exists())
    }

    @Test
    fun isInsideTrash_detectsTrashPaths() = runBlocking {
        val file = tmp.newFile("normal.txt")
        assertFalse(trashManager.isInsideTrash(file))

        val trashDir = File(tmp.root, TrashManager.TRASH_DIR_NAME)
        val fileInTrash = File(trashDir, "files/some_id")
        assertTrue(trashManager.isInsideTrash(fileInTrash))
    }

    @Test
    fun listTrash_recoversOrphanFiles_withoutMetadata() = runBlocking {
        val trashDir = File(tmp.root, TrashManager.TRASH_DIR_NAME)
        val filesDir = File(trashDir, TrashManager.FILES_DIR_NAME).apply { mkdirs() }
        val infoDir = File(trashDir, TrashManager.INFO_DIR_NAME).apply { mkdirs() }

        // Create an orphan file directly in .zibzip_trash/files/ with NO corresponding JSON in info/
        val orphanId = "1729240000000_abc12345"
        val orphanFile = File(filesDir, orphanId).apply { writeText("stranded content") }

        val items = trashManager.listTrash()
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals(orphanId, item.id)
        assertTrue(item.trashFile.exists())
        assertEquals("stranded content", item.trashFile.readText())

        // Ensure self-healing created the metadata JSON
        val expectedJson = File(infoDir, "$orphanId.json")
        assertTrue(expectedJson.exists())
        val parsed = kotlinx.serialization.json.Json.decodeFromString<TrashedItem>(expectedJson.readText())
        assertEquals(orphanId, parsed.id)
    }

    @Test
    fun parseJson_handlesEscapesAndSpecialCharacters() {
        val json = """
            {
              "id": "123_abc",
              "originalPath": "/storage/emulated/0/Download/\"special\"\npath\\test",
              "originalName": "quote\"and\nnewline,comma:colon.txt",
              "deletedAt": 1729240000000,
              "size": 4096,
              "isDirectory": false
            }
        """.trimIndent()

        val parsed = kotlinx.serialization.json.Json.decodeFromString<TrashedItem>(json)
        assertEquals("123_abc", parsed.id)
        assertEquals("/storage/emulated/0/Download/\"special\"\npath\\test", parsed.originalPath)
        assertEquals("quote\"and\nnewline,comma:colon.txt", parsed.originalName)
        assertEquals(1729240000000L, parsed.deletedAt)
        assertEquals(4096L, parsed.size)
        assertEquals(false, parsed.isDirectory)
    }

    @Test
    fun moveToTrash_handlesSpecialCharactersInFileName() = runBlocking {
        val file = tmp.newFile("file with \"quotes\" & comma, and spaces.txt").apply { writeText("special") }
        val moved = trashManager.moveToTrash(listOf(file))
        assertEquals(1, moved)
        assertFalse(file.exists())

        val items = trashManager.listTrash()
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("file with \"quotes\" & comma, and spaces.txt", item.originalName)

        val restored = trashManager.restore(item)
        assertTrue(restored.exists())
        assertEquals("special", restored.readText())
        assertEquals("file with \"quotes\" & comma, and spaces.txt", restored.name)
    }

    @Test(expected = dev.neroices.zibzip.domain.ZibzipException.Io::class)
    fun restore_throwsWhenTrashFileDoesNotExist() = runBlocking {
        val file = tmp.newFile("missing.txt").apply { writeText("data") }
        trashManager.moveToTrash(listOf(file))
        val item = trashManager.listTrash().first()
        item.trashFile.delete()
        trashManager.restore(item)
        Unit
    }

    @Test
    fun getTrashStats_returnsCorrectCountAndBytes() = runBlocking {
        assertEquals(TrashStats(0, 0L), trashManager.getTrashStats())

        val f1 = tmp.newFile("file1.txt").apply { writeText("hello") }
        val f2 = tmp.newFile("file2.txt").apply { writeText("world!") }
        trashManager.moveToTrash(listOf(f1, f2))

        val stats = trashManager.getTrashStats()
        assertEquals(2, stats.count)
        assertEquals(11L, stats.totalBytes)

        trashManager.emptyTrash()
        val statsAfterEmpty = trashManager.getTrashStats()
        assertEquals(0, statsAfterEmpty.count)
        assertEquals(0L, statsAfterEmpty.totalBytes)
    }
}

