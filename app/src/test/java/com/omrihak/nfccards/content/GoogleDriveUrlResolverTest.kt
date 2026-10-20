package com.omrihak.nfccards.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleDriveUrlResolverTest {
    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val direct = "https://drive.usercontent.google.com/download?id=$id&export=download"

    @Test fun shareViewUrl() = assertEquals(id, GoogleDriveUrlResolver.extractFileId("https://drive.google.com/file/d/$id/view?usp=sharing"))
    @Test fun multiAccountShareUrl() = assertEquals(id, GoogleDriveUrlResolver.extractFileId("https://drive.google.com/file/u/0/d/$id/view"))
    @Test fun openUrl() = assertEquals(id, GoogleDriveUrlResolver.extractFileId("https://drive.google.com/open?id=$id"))
    @Test fun ucUrl() = assertEquals(id, GoogleDriveUrlResolver.extractFileId("https://drive.google.com/uc?export=download&id=$id"))
    @Test fun usercontentUrl() = assertEquals(id, GoogleDriveUrlResolver.extractFileId(direct))
    @Test fun folderHasNoId() = assertNull(GoogleDriveUrlResolver.extractFileId("https://drive.google.com/drive/folders/$id"))

    @Test fun candidatesForShareUrl() {
        val c = GoogleDriveUrlResolver.downloadCandidates("https://drive.google.com/file/d/$id/view")
        assertEquals(listOf(direct, "$direct&confirm=t"), c)
    }

    @Test fun nonDriveUrlPassesThrough() {
        val url = "https://example.com/songs/a.mp3"
        assertFalse(GoogleDriveUrlResolver.isDriveUrl(url))
        assertEquals(listOf(url), GoogleDriveUrlResolver.downloadCandidates(url))
    }

    @Test fun lookalikeHostIsNotDrive() {
        assertFalse(GoogleDriveUrlResolver.isDriveUrl("https://drive.google.com.evil.example/file/d/$id/view"))
    }

    @Test fun driveFolderIsInvalid() {
        try {
            GoogleDriveUrlResolver.downloadCandidates("https://drive.google.com/drive/folders/$id")
            fail("expected exception")
        } catch (e: ContentException) {
            assertEquals(ErrorKind.INVALID_URL, e.kind)
        }
    }

    @Test fun garbageIsNotDrive() = assertTrue(!GoogleDriveUrlResolver.isDriveUrl("not a url at all"))
}
