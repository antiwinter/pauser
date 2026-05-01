package com.opentune.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare

data class SmbCredentials(
    val host: String,
    val shareName: String,
    val username: String,
    val password: String,
    val domain: String? = null,
)

class SmbSession private constructor(
    private val client: SMBClient,
    val share: DiskShare,
) : AutoCloseable {

    override fun close() {
        runCatching { share.close() }
        runCatching { client.close() }
    }

    companion object {
        fun open(credentials: SmbCredentials): SmbSession {
            val client = SMBClient()
            val connection = client.connect(credentials.host)
            val auth = AuthenticationContext(
                credentials.username,
                credentials.password.toCharArray(),
                credentials.domain,
            )
            val session = connection.authenticate(auth)
            val share = session.connectShare(credentials.shareName) as DiskShare
            return SmbSession(client, share)
        }
    }
}

fun DiskShare.listDirectory(path: String): List<SmbListEntry> {
    val normalized = path.replace('/', '\\').trim('\\')
    val listPath = normalized
    val infos: List<FileIdBothDirectoryInformation> = list(listPath)
    return infos.asSequence()
        .filter { it.fileName !in setOf(".", "..") }
        .map { info ->
            val name = info.fileName
            val fullPath = if (normalized.isEmpty()) name else "$normalized\\$name"
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0
            SmbListEntry(
                name = name,
                path = fullPath.replace('\\', '/'),
                isDirectory = isDir,
            )
        }
        .toList()
}

data class SmbListEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
)
