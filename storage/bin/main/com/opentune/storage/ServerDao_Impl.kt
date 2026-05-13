package com.opentune.storage

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class ServerDao_Impl(
  __db: RoomDatabase,
) : ServerDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfServerEntity: EntityInsertAdapter<ServerEntity>

  private val __updateAdapterOfServerEntity: EntityDeleteOrUpdateAdapter<ServerEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfServerEntity = object : EntityInsertAdapter<ServerEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR ABORT INTO `servers` (`sourceId`,`protocol`,`displayName`,`fieldsJson`,`createdAtEpochMs`,`updatedAtEpochMs`) VALUES (?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ServerEntity) {
        statement.bindText(1, entity.sourceId)
        statement.bindText(2, entity.protocol)
        statement.bindText(3, entity.displayName)
        statement.bindText(4, entity.fieldsJson)
        statement.bindLong(5, entity.createdAtEpochMs)
        statement.bindLong(6, entity.updatedAtEpochMs)
      }
    }
    this.__updateAdapterOfServerEntity = object : EntityDeleteOrUpdateAdapter<ServerEntity>() {
      protected override fun createQuery(): String =
          "UPDATE OR ABORT `servers` SET `sourceId` = ?,`protocol` = ?,`displayName` = ?,`fieldsJson` = ?,`createdAtEpochMs` = ?,`updatedAtEpochMs` = ? WHERE `sourceId` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: ServerEntity) {
        statement.bindText(1, entity.sourceId)
        statement.bindText(2, entity.protocol)
        statement.bindText(3, entity.displayName)
        statement.bindText(4, entity.fieldsJson)
        statement.bindLong(5, entity.createdAtEpochMs)
        statement.bindLong(6, entity.updatedAtEpochMs)
        statement.bindText(7, entity.sourceId)
      }
    }
  }

  public override suspend fun insert(server: ServerEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __insertAdapterOfServerEntity.insert(_connection, server)
  }

  public override suspend fun update(server: ServerEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __updateAdapterOfServerEntity.handle(_connection, server)
  }

  public override fun observeByProvider(protocol: String): Flow<List<ServerEntity>> {
    val _sql: String = "SELECT * FROM servers WHERE protocol = ? ORDER BY createdAtEpochMs ASC"
    return createFlow(__db, false, arrayOf("servers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, protocol)
        val _columnIndexOfSourceId: Int = getColumnIndexOrThrow(_stmt, "sourceId")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "displayName")
        val _columnIndexOfFieldsJson: Int = getColumnIndexOrThrow(_stmt, "fieldsJson")
        val _columnIndexOfCreatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "createdAtEpochMs")
        val _columnIndexOfUpdatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "updatedAtEpochMs")
        val _result: MutableList<ServerEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ServerEntity
          val _tmpSourceId: String
          _tmpSourceId = _stmt.getText(_columnIndexOfSourceId)
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpFieldsJson: String
          _tmpFieldsJson = _stmt.getText(_columnIndexOfFieldsJson)
          val _tmpCreatedAtEpochMs: Long
          _tmpCreatedAtEpochMs = _stmt.getLong(_columnIndexOfCreatedAtEpochMs)
          val _tmpUpdatedAtEpochMs: Long
          _tmpUpdatedAtEpochMs = _stmt.getLong(_columnIndexOfUpdatedAtEpochMs)
          _item =
              ServerEntity(_tmpSourceId,_tmpProtocol,_tmpDisplayName,_tmpFieldsJson,_tmpCreatedAtEpochMs,_tmpUpdatedAtEpochMs)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeAll(): Flow<List<ServerEntity>> {
    val _sql: String = "SELECT * FROM servers ORDER BY createdAtEpochMs ASC"
    return createFlow(__db, false, arrayOf("servers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfSourceId: Int = getColumnIndexOrThrow(_stmt, "sourceId")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "displayName")
        val _columnIndexOfFieldsJson: Int = getColumnIndexOrThrow(_stmt, "fieldsJson")
        val _columnIndexOfCreatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "createdAtEpochMs")
        val _columnIndexOfUpdatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "updatedAtEpochMs")
        val _result: MutableList<ServerEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ServerEntity
          val _tmpSourceId: String
          _tmpSourceId = _stmt.getText(_columnIndexOfSourceId)
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpFieldsJson: String
          _tmpFieldsJson = _stmt.getText(_columnIndexOfFieldsJson)
          val _tmpCreatedAtEpochMs: Long
          _tmpCreatedAtEpochMs = _stmt.getLong(_columnIndexOfCreatedAtEpochMs)
          val _tmpUpdatedAtEpochMs: Long
          _tmpUpdatedAtEpochMs = _stmt.getLong(_columnIndexOfUpdatedAtEpochMs)
          _item =
              ServerEntity(_tmpSourceId,_tmpProtocol,_tmpDisplayName,_tmpFieldsJson,_tmpCreatedAtEpochMs,_tmpUpdatedAtEpochMs)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getBySourceId(sourceId: String): ServerEntity? {
    val _sql: String = "SELECT * FROM servers WHERE sourceId = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, sourceId)
        val _columnIndexOfSourceId: Int = getColumnIndexOrThrow(_stmt, "sourceId")
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "displayName")
        val _columnIndexOfFieldsJson: Int = getColumnIndexOrThrow(_stmt, "fieldsJson")
        val _columnIndexOfCreatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "createdAtEpochMs")
        val _columnIndexOfUpdatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "updatedAtEpochMs")
        val _result: ServerEntity?
        if (_stmt.step()) {
          val _tmpSourceId: String
          _tmpSourceId = _stmt.getText(_columnIndexOfSourceId)
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpFieldsJson: String
          _tmpFieldsJson = _stmt.getText(_columnIndexOfFieldsJson)
          val _tmpCreatedAtEpochMs: Long
          _tmpCreatedAtEpochMs = _stmt.getLong(_columnIndexOfCreatedAtEpochMs)
          val _tmpUpdatedAtEpochMs: Long
          _tmpUpdatedAtEpochMs = _stmt.getLong(_columnIndexOfUpdatedAtEpochMs)
          _result =
              ServerEntity(_tmpSourceId,_tmpProtocol,_tmpDisplayName,_tmpFieldsJson,_tmpCreatedAtEpochMs,_tmpUpdatedAtEpochMs)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteBySourceId(sourceId: String) {
    val _sql: String = "DELETE FROM servers WHERE sourceId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, sourceId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
