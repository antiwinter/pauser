package com.insomnia.provider.js

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.io.File

/**
 * Negative-path tests for [JarLoader]'s public primitives.
 * Happy path needs a real DEX JAR; we verify the failure modes a misbehaving
 * provider hits: missing loader, missing instance handle, missing ClassLoader child.
 */
class JarLoaderPrimitivesTest {

    private lateinit var sandboxRoot: File
    private lateinit var loader: JarLoader

    @Before
    fun setUp() {
        sandboxRoot = Files.createTempDirectory("insomnia-jar-loader-test").toFile()
        loader = JarLoader(sandboxRoot, OkHttpClient())
    }

    @After
    fun tearDown() {
        sandboxRoot.deleteRecursively()
    }

    @Test
    fun loadClass_unknownUrl_throws() {
        val e = assertThrows(IllegalStateException::class.java) {
            loader.loadClass("https://example.com/missing.jar", "com.example.Foo")
        }
        assertTrue(e.message!!.contains("JAR not loaded"))
    }

    @Test
    fun registerLoader_unknownHandle_throws() {
        val e = assertThrows(IllegalStateException::class.java) {
            loader.registerLoader("secondary:abc", "nonexistent-handle")
        }
        assertTrue(e.message!!.contains("Instance not found"))
    }

    @Test
    fun adoptParent_unknownChildKey_throws() {
        val e = assertThrows(IllegalStateException::class.java) {
            loader.adoptParent("missing", "context")
        }
        assertTrue(e.message!!.contains("Child not found"))
    }


    @Test
    fun clear_removesEverything() {
        // Can't populate without a real JAR; just verify clear() is safe on empty state.
        loader.clear()
        assertTrue(sandboxRoot.exists())
    }
}
