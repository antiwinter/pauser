package com.insomnia.provider.js

import java.io.File
import java.nio.file.attribute.PosixFilePermission

/**
 * Caches by inode so repeated calls (e.g. on every JAR load) skip the syscall when the
 * filesystem object hasn't changed. Android re-creates code_cache under low storage;
 * a new inode is the trigger to re-chmod.
 */
internal object DexFilePermissions {

    private val chmodedInodes = java.util.concurrent.ConcurrentHashMap<File, Long>()

    private val READ_ONLY_FOR_ART: Set<PosixFilePermission> = java.util.HashSet(listOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE,
    ))

    fun chmodForDex(file: File) {
        val inode = runCatching {
            java.nio.file.Files.getAttribute(file.toPath(), "unix:ino") as? Long
        }.getOrNull()
        // New inode (or first sighting) → chmod once.
        if (inode == null || chmodedInodes[file] != inode) {
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(file.toPath(), READ_ONLY_FOR_ART)
            }
            chmodedInodes[file] = inode ?: -1L
        }
    }
}
