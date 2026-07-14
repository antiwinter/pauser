package com.insomnia.provider.js

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class HostApisSandboxTest {
    private lateinit var sandboxRoot: File
    private lateinit var host: HostApis

    @Before
    fun setUp() {
        val base = Files.createTempDirectory("insomnia-hostapis-").toFile()
        sandboxRoot = File(base, "sandbox").apply { mkdirs() }
        host = HostApis("test-provider", sandboxRoot)
    }

    @After
    fun tearDown() {
        sandboxRoot.parentFile?.deleteRecursively()
    }


    @Test
    fun write_thenRead_relativePath_roundTripsUtf8() {
        host.handleFs("write", """{"path":"foo/bar.txt","content":"hello"}""")
        val read = host.handleFs("read", """{"path":"foo/bar.txt"}""")
        assertEquals(JsonPrimitive("hello").toString(), read)
    }

    @Test
    fun write_absolutePath_isNormalizedIntoSandbox() {
        host.handleFs("write", """{"path":"/etc/passwd","content":"x"}""")
        val landed = File(sandboxRoot, "etc/passwd")
        assertTrue(landed.exists())
        assertTrue(landed.canonicalPath.startsWith(sandboxRoot.canonicalPath))
    }

    @Test
    fun write_dotDotSegment_throwsSecurityException() {
        val ex = assertThrows(SecurityException::class.java) {
            host.handleFs("write", """{"path":"../escape.txt","content":"x"}""")
        }
        assertTrue(ex.message!!.contains(".."))
    }

    @Test
    fun write_nestedDotDot_throwsSecurityException() {
        assertThrows(SecurityException::class.java) {
            host.handleFs("write", """{"path":"a/../../escape.txt","content":"x"}""")
        }
    }

    @Test
    fun exists_afterWrite_returnsTrue() {
        host.handleFs("write", """{"path":"present.txt","content":"x"}""")
        assertEquals(JsonPrimitive(true).toString(), host.handleFs("exists", """{"path":"present.txt"}"""))
        assertEquals(JsonPrimitive(false).toString(), host.handleFs("exists", """{"path":"missing.txt"}"""))
    }

    @Test
    fun delete_removesFile() {
        host.handleFs("write", """{"path":"transient.txt","content":"x"}""")
        assertEquals(JsonPrimitive(true).toString(), host.handleFs("delete", """{"path":"transient.txt"}"""))
        assertEquals(JsonPrimitive(false).toString(), host.handleFs("exists", """{"path":"transient.txt"}"""))
    }

    @Test
    fun read_base64_roundTripsBinaryBytes() {
        val payload = "binary\u0000\u00ffbytes"
        val b64 = java.util.Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        host.handleFs("write", """{"path":"blob.bin","content":${JsonPrimitive(b64)},"encoding":"base64"}""")
        val read = host.handleFs("read", """{"path":"blob.bin","encoding":"base64"}""")
        assertEquals(JsonPrimitive(b64).toString(), read)
    }

    @Test
    fun checksum_sha256_defaultEncoding_matchesJavaMessageDigest() {
        val input = "https://example.com" + "user-123"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val actual = host.handleCrypto("checksum", """{"input":${JsonPrimitive(input)}}""")
        assertEquals(JsonPrimitive(expected).toString(), actual)
    }

    @Test
    fun checksum_md5_overBase64Bytes_matchesJavaMessageDigest() {
        val bytes = "binary".toByteArray(Charsets.UTF_8)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val expected = MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val args = buildJsonObject {
            put("input", JsonPrimitive(b64))
            put("encoding", JsonPrimitive("base64"))
            put("algo", JsonPrimitive("md5"))
        }
        val actual = host.handleCrypto("checksum", args.toString())
        assertEquals(JsonPrimitive(expected).toString(), actual)
    }

    @Test
    fun checksum_unknownAlgo_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            host.handleCrypto("checksum", """{"input":"x","algo":"blake2"}""")
        }
    }


}