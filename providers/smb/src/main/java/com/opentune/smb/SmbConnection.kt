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
            val auth = when {
                credentials.username.isBlank() && credentials.password.isBlank() ->
                    AuthenticationContext.guest()
                else ->
                    AuthenticationContext(
                        credentials.username,
                        credentials.password.toCharArray(),
                        credentials.domain,
                    )
            }
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
            val attrs = info.fileAttributes.toLong()
            val dirMask = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value.toLong()
            val isDir = (attrs and dirMask) != 0L
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

/** Case-insensitive filter for SMB search (current directory only). */
fun List<SmbListEntry>.filterByName(query: String): List<SmbListEntry> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.name.contains(q, ignoreCase = true) }
}
