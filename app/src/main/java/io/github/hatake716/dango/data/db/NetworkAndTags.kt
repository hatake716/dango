package io.github.hatake716.dango.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** ネットワーク接続の設定（SPEC §7.2。資格情報は EncryptedSharedPreferences 側） */
@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** smb / sftp / webdav / ftp */
    val protocol: String,
    val host: String,
    val port: Int,
    /** SMB は共有名、WebDAV/SFTP/FTP は初期パス */
    val sharePath: String,
    val username: String,
    val savePassword: Boolean,
)

@Dao
interface ConnectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(connection: ConnectionEntity): Long

    @Query("SELECT * FROM connections ORDER BY name")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun byId(id: Long): ConnectionEntity?

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun delete(id: Long)
}

/** エントリへのタグ付け（SPEC §6.3: Room に保存。7色、ユーザー定義は M6 以降） */
@Entity(tableName = "entry_tags", primaryKeys = ["path", "tag"])
data class EntryTagEntity(
    val path: String,
    /** red / orange / yellow / green / blue / purple / gray */
    val tag: String,
)

@Dao
interface EntryTagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(tag: EntryTagEntity)

    @Query("DELETE FROM entry_tags WHERE path = :path AND tag = :tag")
    suspend fun remove(path: String, tag: String)

    @Query("SELECT * FROM entry_tags WHERE path IN (:paths)")
    suspend fun forPaths(paths: List<String>): List<EntryTagEntity>

    @Query("SELECT path FROM entry_tags WHERE tag = :tag ORDER BY path")
    suspend fun pathsWithTag(tag: String): List<String>

    @Query("UPDATE entry_tags SET path = :newPath WHERE path = :oldPath")
    suspend fun rename(oldPath: String, newPath: String)

    @Query("DELETE FROM entry_tags WHERE path = :path")
    suspend fun removeAll(path: String)
}
