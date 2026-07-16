package com.insomnia.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.contract.EntryList
import com.insomnia.content.contract.QueryOptions
import com.insomnia.content.contract.SortField
import com.insomnia.content.contract.SortOrder
import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.EndpointValidationResult
import com.insomnia.content.contract.ProviderStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet

private const val SMB_LOG = "InsomniaPlayer"

class SmbClient(
    private val fields: SmbServerFieldsJson,
) : EndpointClient() {

    override val progressIntervalMs: Long = 0L

    private fun credentials() = SmbCredentials(
        host = fields.host,
        shareName = fields.shareName,
        username = fields.username,
        password = fields.password,
        domain = fields.domain,
    )

    override suspend fun test(): EndpointValidationResult = withContext(Dispatchers.IO) {
        try {
            val session = SmbSession.open(credentials())
            session.close()
            EndpointValidationResult.Success(
                fields = buildMap {
                    put("host", fields.host)
                    put("share_name", fields.shareName)
                    put("username", fields.username)
                    put("password", fields.password)
                    put("name", fields.shareName)
                    fields.domain?.let { put("domain", it) }
                },
            )
        } catch (e: Exception) {
            EndpointValidationResult.Error(e.message ?: "SMB validation failed")
        }
    }

    override suspend fun listEntry(
        location: String?,
        startIndex: Int,
        limit: Int,
        options: QueryOptions,
    ): EntryList {
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share
                val searchTerm = options.searchTerm?.trim().orEmpty()
                val raw = if (searchTerm.isNotEmpty()) {
                    share.listDirectory(location ?: "").filterByName(searchTerm)
                } else {
                    share.listDirectory(location ?: "")
                }
                val all = raw.map { mapEntry(it, location) }
                val sorted = when (options.sortBy) {
                    SortField.Title, SortField.IndexNumber, null -> all.sortedBy { it.title }
                    else -> all.sortedBy { it.title }
                }.let { if (options.sortOrder == SortOrder.Descending) it.reversed() else it }
                val slice = sorted.drop(startIndex).take(limit)
                EntryList(items = slice, totalCount = all.size)
            } finally {
                session.close()
            }
        }
    }

    override suspend fun getEntries(itemRefs: List<String>): EntryList {
        if (itemRefs.isEmpty()) return EntryList(emptyList(), 0)
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share
                val items = share.listDirectory("")
                    .filter { it.path in itemRefs }
                    .map { mapEntry(it, null) }
                EntryList(items = items, totalCount = items.size)
            } finally {
                session.close()
            }
        }
    }

    override suspend fun openStream(itemRef: String): ProviderStream {
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            val smbFile = session.share.openFile(
                itemRef,
                EnumSet.of(AccessMask.GENERIC_READ), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN, null,
            )
            SmbProviderStream(smbFile, session)
        }
    }

    private class SmbProviderStream(
        private val file: SmbFile,
        private val session: SmbSession,
    ) : ProviderStream {
        override suspend fun getSize(): Long =
            file.getFileInformation(FileStandardInformation::class.java).endOfFile

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val r = file.read(buffer, position, offset, size)
            return if (r <= 0) 0 else r
        }

        override fun close() {
            runCatching { file.close() }
            session.close()
        }
    }

    private fun mapEntry(e: SmbListEntry, parentLocation: String?): EntryInfo {
        val kind = if (e.isDirectory) "Folder" else "Unknown"
        return EntryInfo(
            ref = e.path,
            title = e.name + if (e.isDirectory) "/" else "",
            type = kind,
            cover = null,
            filename = e.name,
            parentRef = parentLocation ?: e.path.substringBeforeLast('\\'),
        )
    }
}
