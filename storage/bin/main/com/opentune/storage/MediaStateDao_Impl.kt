package com.opentune.storage

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Float
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
public class MediaStateDao_Impl(
  __db: RoomDatabase,
) : MediaStateDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfMediaStateEntity: EntityInsertAdapter<MediaStateEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfMediaStateEntity = object : EntityInsertAdapter<MediaStateEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `media_state` (`protocol`,`sourceId`,`itemId`,`positionMs`,`playbackSpeed`,`isFavorite`,`title`,`type`,`coverCachePath`,`selectedSubtitleTrackId`,`selectedAudioTrackId`,`updatedAtEpochMs`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: MediaStateEntity) {
        statement.bindText(1, entity.protocol)
        statement.bindText(2, entity.sourceId)
        statement.bindText(3, entity.itemId)
        statement.bindLong(4, entity.positionMs)
        statement.bindDouble(5, entity.playbackSpeed.toDouble())
        val _tmp: Int = if (entity.isFavorite) 1 else 0
        statement.bindLong(6, _tmp.toLong())
        val _tmpTitle: String? = entity.title
        if (_tmpTitle == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpTitle)
        }
        val _tmpType: String? = entity.type
        if (_tmpType == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpType)
        }
        val _tmpCoverCachePath: String? = entity.coverCachePath
        if (_tmpCoverCachePath == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpCoverCachePath)
        }
        val _tmpSelectedSubtitleTrackId: String? = entity.selectedSubtitleTrackId
        if (_tmpSelectedSubtitleTrackId == null) {
          statement.bindNull(10)
        } else {
          statement.bindText(10, _tmpSelectedSubtitleTrackId)
        }
        val _tmpSelectedAudioTrackId: String? = entity.selectedAudioTrackId
        if (_tmpSelectedAudioTrackId == null) {
          statement.bindNull(11)
        } else {
          statement.bindText(11, _tmpSelectedAudioTrackId)
        }
        statement.bindLong(12, entity.updatedAtEpochMs)
      }
    }
  }

  public override suspend fun upsert(entity: MediaStateEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfMediaStateEntity.insert(_connection, entity)
  }

  public override suspend fun `get`(
    protocol: String,
    sourceId: String,
    itemId: String,
  ): MediaStateEntity? {
    val _sql: String =
        "SELECT * FROM media_state WHERE protocol = ? AND sourceId = ? AND itemId = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 2
        _stmt.bindText(_argIndex, sourceId)
        _argIndex = 3
        _stmt.bindText(_argIndex, itemId)
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceId: Int = getColumnIndexOrThrow(_stmt, "sourceId")
        val _columnIndexOfItemId: Int = getColumnIndexOrThrow(_stmt, "itemId")
        val _columnIndexOfPositionMs: Int = getColumnIndexOrThrow(_stmt, "positionMs")
        val _columnIndexOfPlaybackSpeed: Int = getColumnIndexOrThrow(_stmt, "playbackSpeed")
        val _columnIndexOfIsFavorite: Int = getColumnIndexOrThrow(_stmt, "isFavorite")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfCoverCachePath: Int = getColumnIndexOrThrow(_stmt, "coverCachePath")
        val _columnIndexOfSelectedSubtitleTrackId: Int = getColumnIndexOrThrow(_stmt,
            "selectedSubtitleTrackId")
        val _columnIndexOfSelectedAudioTrackId: Int = getColumnIndexOrThrow(_stmt,
            "selectedAudioTrackId")
        val _columnIndexOfUpdatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "updatedAtEpochMs")
        val _result: MediaStateEntity?
        if (_stmt.step()) {
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpSourceId: String
          _tmpSourceId = _stmt.getText(_columnIndexOfSourceId)
          val _tmpItemId: String
          _tmpItemId = _stmt.getText(_columnIndexOfItemId)
          val _tmpPositionMs: Long
          _tmpPositionMs = _stmt.getLong(_columnIndexOfPositionMs)
          val _tmpPlaybackSpeed: Float
          _tmpPlaybackSpeed = _stmt.getDouble(_columnIndexOfPlaybackSpeed).toFloat()
          val _tmpIsFavorite: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsFavorite).toInt()
          _tmpIsFavorite = _tmp != 0
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpType: String?
          if (_stmt.isNull(_columnIndexOfType)) {
            _tmpType = null
          } else {
            _tmpType = _stmt.getText(_columnIndexOfType)
          }
          val _tmpCoverCachePath: String?
          if (_stmt.isNull(_columnIndexOfCoverCachePath)) {
            _tmpCoverCachePath = null
          } else {
            _tmpCoverCachePath = _stmt.getText(_columnIndexOfCoverCachePath)
          }
          val _tmpSelectedSubtitleTrackId: String?
          if (_stmt.isNull(_columnIndexOfSelectedSubtitleTrackId)) {
            _tmpSelectedSubtitleTrackId = null
          } else {
            _tmpSelectedSubtitleTrackId = _stmt.getText(_columnIndexOfSelectedSubtitleTrackId)
          }
          val _tmpSelectedAudioTrackId: String?
          if (_stmt.isNull(_columnIndexOfSelectedAudioTrackId)) {
            _tmpSelectedAudioTrackId = null
          } else {
            _tmpSelectedAudioTrackId = _stmt.getText(_columnIndexOfSelectedAudioTrackId)
          }
          val _tmpUpdatedAtEpochMs: Long
          _tmpUpdatedAtEpochMs = _stmt.getLong(_columnIndexOfUpdatedAtEpochMs)
          _result =
              MediaStateEntity(_tmpProtocol,_tmpSourceId,_tmpItemId,_tmpPositionMs,_tmpPlaybackSpeed,_tmpIsFavorite,_tmpTitle,_tmpType,_tmpCoverCachePath,_tmpSelectedSubtitleTrackId,_tmpSelectedAudioTrackId,_tmpUpdatedAtEpochMs)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeForSource(protocol: String, sourceId: String):
      Flow<List<MediaStateEntity>> {
    val _sql: String =
        "SELECT * FROM media_state WHERE protocol = ? AND sourceId = ? ORDER BY updatedAtEpochMs DESC"
    return createFlow(__db, false, arrayOf("media_state")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 2
        _stmt.bindText(_argIndex, sourceId)
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceId: Int = getColumnIndexOrThrow(_stmt, "sourceId")
        val _columnIndexOfItemId: Int = getColumnIndexOrThrow(_stmt, "itemId")
        val _columnIndexOfPositionMs: Int = getColumnIndexOrThrow(_stmt, "positionMs")
        val _columnIndexOfPlaybackSpeed: Int = getColumnIndexOrThrow(_stmt, "playbackSpeed")
        val _columnIndexOfIsFavorite: Int = getColumnIndexOrThrow(_stmt, "isFavorite")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfCoverCachePath: Int = getColumnIndexOrThrow(_stmt, "coverCachePath")
        val _columnIndexOfSelectedSubtitleTrackId: Int = getColumnIndexOrThrow(_stmt,
            "selectedSubtitleTrackId")
        val _columnIndexOfSelectedAudioTrackId: Int = getColumnIndexOrThrow(_stmt,
            "selectedAudioTrackId")
        val _columnIndexOfUpdatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "updatedAtEpochMs")
        val _result: MutableList<MediaStateEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: MediaStateEntity
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpSourceId: String
          _tmpSourceId = _stmt.getText(_columnIndexOfSourceId)
          val _tmpItemId: String
          _tmpItemId = _stmt.getText(_columnIndexOfItemId)
          val _tmpPositionMs: Long
          _tmpPositionMs = _stmt.getLong(_columnIndexOfPositionMs)
          val _tmpPlaybackSpeed: Float
          _tmpPlaybackSpeed = _stmt.getDouble(_columnIndexOfPlaybackSpeed).toFloat()
          val _tmpIsFavorite: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsFavorite).toInt()
          _tmpIsFavorite = _tmp != 0
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpType: String?
          if (_stmt.isNull(_columnIndexOfType)) {
            _tmpType = null
          } else {
            _tmpType = _stmt.getText(_columnIndexOfType)
          }
          val _tmpCoverCachePath: String?
          if (_stmt.isNull(_columnIndexOfCoverCachePath)) {
            _tmpCoverCachePath = null
          } else {
            _tmpCoverCachePath = _stmt.getText(_columnIndexOfCoverCachePath)
          }
          val _tmpSelectedSubtitleTrackId: String?
          if (_stmt.isNull(_columnIndexOfSelectedSubtitleTrackId)) {
            _tmpSelectedSubtitleTrackId = null
          } else {
            _tmpSelectedSubtitleTrackId = _stmt.getText(_columnIndexOfSelectedSubtitleTrackId)
          }
          val _tmpSelectedAudioTrackId: String?
          if (_stmt.isNull(_columnIndexOfSelectedAudioTrackId)) {
            _tmpSelectedAudioTrackId = null
          } else {
            _tmpSelectedAudioTrackId = _stmt.getText(_columnIndexOfSelectedAudioTrackId)
          }
          val _tmpUpdatedAtEpochMs: Long
          _tmpUpdatedAtEpochMs = _stmt.getLong(_columnIndexOfUpdatedAtEpochMs)
          _item =
              MediaStateEntity(_tmpProtocol,_tmpSourceId,_tmpItemId,_tmpPositionMs,_tmpPlaybackSpeed,_tmpIsFavorite,_tmpTitle,_tmpType,_tmpCoverCachePath,_tmpSelectedSubtitleTrackId,_tmpSelectedAudioTrackId,_tmpUpdatedAtEpochMs)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observeAllFavorites(): Flow<List<MediaStateEntity>> {
    val _sql: String =
        "SELECT * FROM media_state WHERE isFavorite = 1 ORDER BY updatedAtEpochMs DESC"
    return createFlow(__db, false, arrayOf("media_state")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfProtocol: Int = getColumnIndexOrThrow(_stmt, "protocol")
        val _columnIndexOfSourceId: Int = getColumnIndexOrThrow(_stmt, "sourceId")
        val _columnIndexOfItemId: Int = getColumnIndexOrThrow(_stmt, "itemId")
        val _columnIndexOfPositionMs: Int = getColumnIndexOrThrow(_stmt, "positionMs")
        val _columnIndexOfPlaybackSpeed: Int = getColumnIndexOrThrow(_stmt, "playbackSpeed")
        val _columnIndexOfIsFavorite: Int = getColumnIndexOrThrow(_stmt, "isFavorite")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfCoverCachePath: Int = getColumnIndexOrThrow(_stmt, "coverCachePath")
        val _columnIndexOfSelectedSubtitleTrackId: Int = getColumnIndexOrThrow(_stmt,
            "selectedSubtitleTrackId")
        val _columnIndexOfSelectedAudioTrackId: Int = getColumnIndexOrThrow(_stmt,
            "selectedAudioTrackId")
        val _columnIndexOfUpdatedAtEpochMs: Int = getColumnIndexOrThrow(_stmt, "updatedAtEpochMs")
        val _result: MutableList<MediaStateEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: MediaStateEntity
          val _tmpProtocol: String
          _tmpProtocol = _stmt.getText(_columnIndexOfProtocol)
          val _tmpSourceId: String
          _tmpSourceId = _stmt.getText(_columnIndexOfSourceId)
          val _tmpItemId: String
          _tmpItemId = _stmt.getText(_columnIndexOfItemId)
          val _tmpPositionMs: Long
          _tmpPositionMs = _stmt.getLong(_columnIndexOfPositionMs)
          val _tmpPlaybackSpeed: Float
          _tmpPlaybackSpeed = _stmt.getDouble(_columnIndexOfPlaybackSpeed).toFloat()
          val _tmpIsFavorite: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsFavorite).toInt()
          _tmpIsFavorite = _tmp != 0
          val _tmpTitle: String?
          if (_stmt.isNull(_columnIndexOfTitle)) {
            _tmpTitle = null
          } else {
            _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          }
          val _tmpType: String?
          if (_stmt.isNull(_columnIndexOfType)) {
            _tmpType = null
          } else {
            _tmpType = _stmt.getText(_columnIndexOfType)
          }
          val _tmpCoverCachePath: String?
          if (_stmt.isNull(_columnIndexOfCoverCachePath)) {
            _tmpCoverCachePath = null
          } else {
            _tmpCoverCachePath = _stmt.getText(_columnIndexOfCoverCachePath)
          }
          val _tmpSelectedSubtitleTrackId: String?
          if (_stmt.isNull(_columnIndexOfSelectedSubtitleTrackId)) {
            _tmpSelectedSubtitleTrackId = null
          } else {
            _tmpSelectedSubtitleTrackId = _stmt.getText(_columnIndexOfSelectedSubtitleTrackId)
          }
          val _tmpSelectedAudioTrackId: String?
          if (_stmt.isNull(_columnIndexOfSelectedAudioTrackId)) {
            _tmpSelectedAudioTrackId = null
          } else {
            _tmpSelectedAudioTrackId = _stmt.getText(_columnIndexOfSelectedAudioTrackId)
          }
          val _tmpUpdatedAtEpochMs: Long
          _tmpUpdatedAtEpochMs = _stmt.getLong(_columnIndexOfUpdatedAtEpochMs)
          _item =
              MediaStateEntity(_tmpProtocol,_tmpSourceId,_tmpItemId,_tmpPositionMs,_tmpPlaybackSpeed,_tmpIsFavorite,_tmpTitle,_tmpType,_tmpCoverCachePath,_tmpSelectedSubtitleTrackId,_tmpSelectedAudioTrackId,_tmpUpdatedAtEpochMs)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updatePosition(
    protocol: String,
    sourceId: String,
    itemId: String,
    positionMs: Long,
    now: Long,
  ) {
    val _sql: String =
        "UPDATE media_state SET positionMs = ?, updatedAtEpochMs = ? WHERE protocol = ? AND sourceId = ? AND itemId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, positionMs)
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 4
        _stmt.bindText(_argIndex, sourceId)
        _argIndex = 5
        _stmt.bindText(_argIndex, itemId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateSpeed(
    protocol: String,
    sourceId: String,
    itemId: String,
    speed: Float,
    now: Long,
  ) {
    val _sql: String =
        "UPDATE media_state SET playbackSpeed = ?, updatedAtEpochMs = ? WHERE protocol = ? AND sourceId = ? AND itemId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindDouble(_argIndex, speed.toDouble())
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 4
        _stmt.bindText(_argIndex, sourceId)
        _argIndex = 5
        _stmt.bindText(_argIndex, itemId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateFavorite(
    protocol: String,
    sourceId: String,
    itemId: String,
    isFavorite: Boolean,
    title: String?,
    type: String?,
    now: Long,
  ) {
    val _sql: String =
        "UPDATE media_state SET isFavorite = ?, title = ?, type = ?, updatedAtEpochMs = ? WHERE protocol = ? AND sourceId = ? AND itemId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (isFavorite) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _argIndex = 2
        if (title == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, title)
        }
        _argIndex = 3
        if (type == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, type)
        }
        _argIndex = 4
        _stmt.bindLong(_argIndex, now)
        _argIndex = 5
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 6
        _stmt.bindText(_argIndex, sourceId)
        _argIndex = 7
        _stmt.bindText(_argIndex, itemId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateCoverCache(
    protocol: String,
    sourceId: String,
    itemId: String,
    path: String?,
    now: Long,
  ) {
    val _sql: String =
        "UPDATE media_state SET coverCachePath = ?, updatedAtEpochMs = ? WHERE protocol = ? AND sourceId = ? AND itemId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        if (path == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, path)
        }
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 4
        _stmt.bindText(_argIndex, sourceId)
        _argIndex = 5
        _stmt.bindText(_argIndex, itemId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateSubtitleTrack(
    protocol: String,
    sourceId: String,
    itemId: String,
    id: String?,
    now: Long,
  ) {
    val _sql: String =
        "UPDATE media_state SET selectedSubtitleTrackId = ?, updatedAtEpochMs = ? WHERE protocol = ? AND sourceId = ? AND itemId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        if (id == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, id)
        }
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 4
        _stmt.bindText(_argIndex, sourceId)
        _argIndex = 5
        _stmt.bindText(_argIndex, itemId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateAudioTrack(
    protocol: String,
    sourceId: String,
    itemId: String,
    id: String?,
    now: Long,
  ) {
    val _sql: String =
        "UPDATE media_state SET selectedAudioTrackId = ?, updatedAtEpochMs = ? WHERE protocol = ? AND sourceId = ? AND itemId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        if (id == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, id)
        }
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 4
        _stmt.bindText(_argIndex, sourceId)
        _argIndex = 5
        _stmt.bindText(_argIndex, itemId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteBySource(sourceId: String) {
    val _sql: String = "DELETE FROM media_state WHERE sourceId = ?"
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

  public override suspend fun delete(
    protocol: String,
    sourceId: String,
    itemId: String,
  ) {
    val _sql: String = "DELETE FROM media_state WHERE protocol = ? AND sourceId = ? AND itemId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, protocol)
        _argIndex = 2
        _stmt.bindText(_argIndex, sourceId)
        _argIndex = 3
        _stmt.bindText(_argIndex, itemId)
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
