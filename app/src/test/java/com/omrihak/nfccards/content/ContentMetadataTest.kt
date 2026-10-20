package com.omrihak.nfccards.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ContentMetadataTest {
    private fun kindOf(json: String): ErrorKind {
        try {
            ContentMetadata.parse(json)
        } catch (e: ContentException) {
            return e.kind
        }
        fail("expected ContentException")
        error("unreachable")
    }

    @Test fun audioWithImage() {
        val m = ContentMetadata.parse("""{"type":"audio","media":"https://a/b.mp3","image":"https://a/c.jpg"}""")
        assertEquals("audio", m.type)
        assertEquals("https://a/b.mp3", m.media)
        assertEquals("https://a/c.jpg", m.image)
    }

    @Test fun videoWithoutImage() {
        val m = ContentMetadata.parse("""{"type":"video","media":"https://a/v.mp4"}""")
        assertNull(m.image)
    }

    @Test fun typeIsCaseInsensitiveAndUnknownFieldsKept() {
        val m = ContentMetadata.parse("""{"type":"AUDIO","media":"https://a/b.mp3","volume":0.5}""")
        assertEquals("audio", m.type)
        assertEquals(0.5, m.raw.getDouble("volume"), 0.0)
    }

    @Test fun jsonNullImageIsAbsent() {
        assertNull(ContentMetadata.parse("""{"type":"audio","media":"https://a/b.mp3","image":null}""").image)
    }

    @Test fun byteOrderMarkIsTolerated() {
        ContentMetadata.parse("﻿" + """{"type":"video","media":"https://a/v.mp4"}""")
    }

    @Test fun malformed() = assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("<html>nope</html>"))
    @Test fun missingType() = assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("""{"media":"https://a/b.mp3"}"""))
    @Test fun missingMedia() = assertEquals(ErrorKind.MISSING_MEDIA, kindOf("""{"type":"audio"}"""))
    @Test fun blankMedia() = assertEquals(ErrorKind.MISSING_MEDIA, kindOf("""{"type":"audio","media":"  "}"""))
    @Test fun futureTypeIsUnsupported() = assertEquals(ErrorKind.UNSUPPORTED, kindOf("""{"type":"playlist"}"""))
    @Test fun badMediaUrl() = assertEquals(ErrorKind.INVALID_URL, kindOf("""{"type":"audio","media":"ftp://x/y"}"""))
    @Test fun badImageUrl() = assertEquals(ErrorKind.INVALID_URL, kindOf("""{"type":"audio","media":"https://a/b.mp3","image":"nope"}"""))
}
