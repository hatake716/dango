package io.github.hatake716.dango.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** ゴミ箱の1項目（SPEC §6.6: 元パスと削除日時を Room に記録） */
@Entity(tableName = "trash_entries")
data class TrashEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 元の絶対パス */
    val originalPath: String,
    /** ゴミ箱ディレクトリ内でのファイル名（衝突回避のため接頭辞付き） */
    val trashedName: String,
    /** 表示用の元ファイル名 */
    val displayName: String,
    val isDir: Boolean,
    val size: Long,
    val deletedAt: Long,
)

@Dao
interface TrashDao {
    @Insert
    suspend fun insert(entry: TrashEntryEntity): Long

    @Query("SELECT * FROM trash_entries ORDER BY deletedAt DESC")
    suspend fun listAll(): List<TrashEntryEntity>

    @Query("SELECT * FROM trash_entries WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrashEntryEntity>

    @Query("SELECT * FROM trash_entries WHERE deletedAt < :threshold")
    suspend fun olderThan(threshold: Long): List<TrashEntryEntity>

    @Query("DELETE FROM trash_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM trash_entries")
    suspend fun deleteAll()
}

@Database(
    entities = [TrashEntryEntity::class, ConnectionEntity::class, EntryTagEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class DangoDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun entryTagDao(): EntryTagDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `connections` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `protocol` TEXT NOT NULL, `host` TEXT NOT NULL, " +
                        "`port` INTEGER NOT NULL, `sharePath` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, `savePassword` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `entry_tags` (" +
                        "`path` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`path`, `tag`))",
                )
            }
        }

        fun build(context: Context): DangoDatabase =
            Room.databaseBuilder(context, DangoDatabase::class.java, "dango.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
