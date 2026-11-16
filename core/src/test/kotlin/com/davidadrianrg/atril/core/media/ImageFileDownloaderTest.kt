package com.davidadrianrg.atril.core.media

import java.io.File
import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageFileDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val client = OkHttpClient()

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
        ByteArray(64) { it.toByte() }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("imgdl").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun image(bytes: ByteArray, type: String = "image/png") =
        MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(bytes))

    @Test
    fun `downloads an image atomically`() {
        server.enqueue(image(png))
        val target = File(dir, "1.png")
        val result = ImageFileDownloader.download(client, server.url("/cover.png").toString(), target)
        assertTrue(result.isSuccess)
        assertArrayEquals(png, target.readBytes())
        assertFalse(File(dir, "1.png.part").exists())
    }

    @Test
    fun `rejects an html page served with 200`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login</html>"))
        val target = File(dir, "1.png")
        assertTrue(ImageFileDownloader.download(client, server.url("/c").toString(), target).isFailure)
        assertFalse(target.exists())
        assertFalse(File(dir, "1.png.part").exists())
    }

    @Test
    fun `rejects bytes that are not an image even with an image content type`() {
        server.enqueue(image("not an image at all".toByteArray()))
        val target = File(dir, "1.png")
        assertTrue(ImageFileDownloader.download(client, server.url("/c").toString(), target).isFailure)
        assertFalse(target.exists())
    }

    @Test
    fun `http error keeps a previous image untouched`() {
        val target = File(dir, "1.png").apply { writeBytes(png) }
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(ImageFileDownloader.download(client, server.url("/c").toString(), target).isFailure)
        assertArrayEquals(png, target.readBytes())
    }

    @Test
    fun `rejects images over the size limit`() {
        server.enqueue(image(png + ByteArray(200)))
        val target = File(dir, "1.png")
        assertTrue(ImageFileDownloader.download(client, server.url("/c").toString(), target, maxBytes = 100).isFailure)
        assertFalse(target.exists())
    }

    @Test
    fun `recognises common image signatures`() {
        assertTrue(ImageFileDownloader.looksLikeImage(png))
        assertTrue(ImageFileDownloader.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertTrue(ImageFileDownloader.looksLikeImage("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()))
        assertTrue(ImageFileDownloader.looksLikeImage("GIF89a".toByteArray()))
        assertFalse(ImageFileDownloader.looksLikeImage("<html>".toByteArray()))
        assertFalse(ImageFileDownloader.looksLikeImage(ByteArray(0)))
    }
}
