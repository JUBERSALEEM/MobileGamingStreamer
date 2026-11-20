package com.jubersaleem.mobilegamingstreamer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlValidatorTest {
    @Test fun acceptsRtmpAndRtmpsEndpoints() {
        assertTrue(StreamUrlValidator.isValidRtmpServerUrl("rtmp://a.rtmp.youtube.com/live2"))
        assertTrue(StreamUrlValidator.isValidRtmpServerUrl("rtmps://live.example.com:443/app/stream"))
        assertTrue(StreamUrlValidator.isValidRtmpServerUrl(" RTMP://stream.example.com/live "))
    }

    @Test fun rejectsUnsupportedOrMissingSchemes() {
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl("https://stream.example.com/live"))
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl("stream.example.com/live"))
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl("ftp://stream.example.com/live"))
    }

    @Test fun rejectsMissingHostAndMalformedInput() {
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl("rtmp:///live"))
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl("rtmps://"))
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl("rtmp://stream example.com/live"))
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl(""))
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl("   "))
    }

    @Test fun rejectsEmbeddedCredentials() {
        assertFalse(StreamUrlValidator.isValidRtmpServerUrl("rtmp://user:password@stream.example.com/live"))
    }
}
