package com.opentune.storage

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class OpenTuneDatabase_Impl : OpenTuneDatabase() {
  private val _serverDao: Lazy<ServerDao> = lazy {
    ServerDao_Impl(this)
  }

  private val _mediaStateDao: Lazy<MediaStateDao> = lazy {
    MediaStateDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(8,
        "0591b339408fdd286870823720e12e70", "808411652f51d448d63cd2e0725f602a") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `servers` (`sourceId` TEXT NOT NULL, `protocol` TEXT NOT NULL, `displayName` TEXT NOT NULL, `fieldsJson` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `media_state` (`protocol` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `playbackSpeed` REAL NOT NULL, `isFavorite` INTEGER NOT NULL, `title` TEXT, `type` TEXT, `coverCachePath` TEXT, `selectedSubtitleTrackId` TEXT, `selectedAudioTrackId` TEXT, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `itemId`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0591b339408fdd286870823720e12e70')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `servers`")
        connection.execSQL("DROP TABLE IF EXISTS `media_state`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection):
          RoomOpenDelegate.ValidationResult {
        val _columnsServers: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsServers.put("sourceId", TableInfo.Column("sourceId", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsServers.put("protocol", TableInfo.Column("protocol", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsServers.put("displayName", TableInfo.Column("displayName", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsServers.put("fieldsJson", TableInfo.Column("fieldsJson", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsServers.put("createdAtEpochMs", TableInfo.Column("createdAtEpochMs", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsServers.put("updatedAtEpochMs", TableInfo.Column("updatedAtEpochMs", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysServers: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesServers: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoServers: TableInfo = TableInfo("servers", _columnsServers, _foreignKeysServers,
            _indicesServers)
        val _existingServers: TableInfo = read(connection, "servers")
        if (!_infoServers.equals(_existingServers)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |servers(com.opentune.storage.ServerEntity).
              | Expected:
              |""".trimMargin() + _infoServers + """
              |
              | Found:
              |""".trimMargin() + _existingServers)
        }
        val _columnsMediaState: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsMediaState.put("protocol", TableInfo.Column("protocol", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("sourceId", TableInfo.Column("sourceId", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("itemId", TableInfo.Column("itemId", "TEXT", true, 2, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("positionMs", TableInfo.Column("positionMs", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("playbackSpeed", TableInfo.Column("playbackSpeed", "REAL", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("isFavorite", TableInfo.Column("isFavorite", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("title", TableInfo.Column("title", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("type", TableInfo.Column("type", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("coverCachePath", TableInfo.Column("coverCachePath", "TEXT", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("selectedSubtitleTrackId",
            TableInfo.Column("selectedSubtitleTrackId", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("selectedAudioTrackId", TableInfo.Column("selectedAudioTrackId",
            "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsMediaState.put("updatedAtEpochMs", TableInfo.Column("updatedAtEpochMs", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysMediaState: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesMediaState: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoMediaState: TableInfo = TableInfo("media_state", _columnsMediaState,
            _foreignKeysMediaState, _indicesMediaState)
        val _existingMediaState: TableInfo = read(connection, "media_state")
        if (!_infoMediaState.equals(_existingMediaState)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |media_state(com.opentune.storage.MediaStateEntity).
              | Expected:
              |""".trimMargin() + _infoMediaState + """
              |
              | Found:
              |""".trimMargin() + _existingMediaState)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "servers", "media_state")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(ServerDao::class, ServerDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(MediaStateDao::class, MediaStateDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override
      fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun serverDao(): ServerDao = _serverDao.value

  public override fun mediaStateDao(): MediaStateDao = _mediaStateDao.value
}
