package dev.neroices.zibzip.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {

    @Test
    fun naturalOrder_sortsNumbersByValue() {
        val sorted = listOf("file10.txt", "file2.txt", "File1.txt", "file02.txt").sortedWith(NaturalOrder)
        assertEquals(listOf("File1.txt", "file2.txt", "file02.txt", "file10.txt"), sorted)
    }

    @Test
    fun archiveFormat_detectsLongestSuffix() {
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.detect("backup.tar.gz"))
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.detect("BACKUP.TGZ"))
        assertEquals(ArchiveFormat.TAR_ZST, ArchiveFormat.detect("backup.tar.zst"))
        assertEquals(ArchiveFormat.ZST, ArchiveFormat.detect("archive.zst"))
        assertEquals(ArchiveFormat.GZ, ArchiveFormat.detect("notes.txt.gz"))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.detect("a.7z"))
        assertNull(ArchiveFormat.detect("photo.jpg"))
        assertNull(ArchiveFormat.detect(".zip"))
    }

    @Test
    fun archiveFormat_stripSuffix() {
        assertEquals("backup", ArchiveFormat.TAR_GZ.stripSuffix("backup.tar.gz"))
        assertEquals("notes.txt", ArchiveFormat.GZ.stripSuffix("notes.txt.gz"))
    }

    @Test
    fun sort_foldersFirst_thenByField() {
        fun f(name: String, dir: Boolean, size: Long = 0) = FileItem("/$name", name, dir, size, 0, false)
        val items = listOf(f("b.txt", false, 5), f("zdir", true), f("a.txt", false, 9), f("adir", true))

        val byName = items.sortedWith(SortOption().comparator()).map { it.name }
        assertEquals(listOf("adir", "zdir", "a.txt", "b.txt"), byName)

        val bySizeDesc = items.sortedWith(SortOption(SortField.Size, ascending = false).comparator()).map { it.name }
        assertEquals(listOf("zdir", "adir", "a.txt", "b.txt"), bySizeDesc)
    }

    @Test
    fun formatSize_isReadable() {
        assertEquals("0 B", formatSize(0))
        assertEquals("1023 B", formatSize(1023))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 MB", formatSize(1_572_864))
        assertEquals("—", formatSize(-1))
    }

    @Test
    fun fileKind_isDetectedFromName() {
        assertEquals(FileKind.Archive, FileTypes.kindOf("x.tar.xz", false))
        assertEquals(FileKind.Image, FileTypes.kindOf("x.PNG", false))
        assertEquals(FileKind.Folder, FileTypes.kindOf("x.png", true))
        assertEquals(FileKind.Document, FileTypes.kindOf("song.lrc", false))
        assertEquals(FileKind.Other, FileTypes.kindOf("README", false))
    }

    @Test
    fun appSettings_showParentFolderDefaultAndCopy() {
        val defaultSettings = dev.neroices.zibzip.data.AppSettings()
        org.junit.Assert.assertTrue(defaultSettings.showParentFolder)

        val disabled = defaultSettings.copy(showParentFolder = false)
        org.junit.Assert.assertFalse(disabled.showParentFolder)
    }

    @Test
    fun extractTarget_customFolder_properties() {
        val targetDirect = dev.neroices.zibzip.ui.files.ExtractTarget.CustomFolder("/path/to/dir", createSubfolder = false)
        assertEquals("/path/to/dir", targetDirect.path)
        org.junit.Assert.assertFalse(targetDirect.createSubfolder)

        val targetSubfolder = dev.neroices.zibzip.ui.files.ExtractTarget.CustomFolder("/path/to/dir", createSubfolder = true)
        assertEquals("/path/to/dir", targetSubfolder.path)
        org.junit.Assert.assertTrue(targetSubfolder.createSubfolder)
    }

    @Test
    fun fileTypes_audioDetection() {
        org.junit.Assert.assertTrue(FileTypes.isAudioFile("song.mp3"))
        org.junit.Assert.assertTrue(FileTypes.isAudioFile("track.FLAC"))
        org.junit.Assert.assertTrue(FileTypes.isAudioFile("recording.wav"))
        org.junit.Assert.assertTrue(FileTypes.isAudioFile("music.m4a"))
        org.junit.Assert.assertTrue(FileTypes.isAudioFile("voice.opus"))
        org.junit.Assert.assertFalse(FileTypes.isAudioFile("photo.png"))
        org.junit.Assert.assertFalse(FileTypes.isAudioFile("document.pdf"))
        org.junit.Assert.assertFalse(FileTypes.isAudioFile("code.kt"))
    }

    @Test
    fun audioTrack_metadataDefaultsAndOverrides() {
        val defaultTrack = dev.neroices.zibzip.audio.AudioTrack(
            path = "/storage/music/song.mp3",
            name = "song.mp3",
            subtitle = "3.5 MB",
            size = 3_500_000L,
        )
        assertEquals("song.mp3", defaultTrack.name)
        assertNull(defaultTrack.title)
        assertNull(defaultTrack.artist)
        assertNull(defaultTrack.albumArt)
        assertNull(defaultTrack.albumAccentColor)

        val updated = defaultTrack.copy(
            title = "Waiting",
            artist = "Dash Berlin",
            albumAccentColor = 0xFF00FF88.toInt(),
        )
        assertEquals("Waiting", updated.title)
        assertEquals("Dash Berlin", updated.artist)
        assertEquals(0xFF00FF88.toInt(), updated.albumAccentColor)
    }

    @Test
    fun fileTypes_extensionlessAndContentProbing() {
        org.junit.Assert.assertTrue(FileTypes.isTextFile("console-ramoops-0"))
        org.junit.Assert.assertTrue(FileTypes.isTextFile("console-ramoops-1"))
        org.junit.Assert.assertTrue(FileTypes.isTextFile("pmsg-ramoops-0"))
        org.junit.Assert.assertTrue(FileTypes.isTextFile("last_kmsg"))
        org.junit.Assert.assertTrue(FileTypes.isTextFile("hosts"))

        val tempDir = java.nio.file.Files.createTempDirectory("zibzip-test").toFile()
        try {
            val textFile = java.io.File(tempDir, "unknown-log").apply {
                writeText("[  0.000000] Linux version 5.10.101-android\n[  0.000001] Command line: console=ramoops\n")
            }
            org.junit.Assert.assertTrue(FileTypes.isTextFile(textFile))

            val emptyFile = java.io.File(tempDir, "empty-note").apply {
                createNewFile()
            }
            org.junit.Assert.assertTrue(FileTypes.isTextFile(emptyFile))

            val binaryFile = java.io.File(tempDir, "firmware-blob").apply {
                writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0x00, 0x02))
            }
            org.junit.Assert.assertFalse(FileTypes.isTextFile(binaryFile))

            val fakePng = java.io.File(tempDir, "image.png").apply {
                writeText("Just some text inside")
            }
            org.junit.Assert.assertFalse(FileTypes.isTextFile(fakePng))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
