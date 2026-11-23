package com.outline.notes.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CollectionEntity::class,
        DocumentEntity::class,
        DocumentFtsEntity::class,
        PendingAttachmentEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class OutlineDatabase : RoomDatabase() {
    abstract fun outlineDao(): OutlineDao

    companion object {
        @Volatile
        private var instance: OutlineDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN archivedAt TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN deletedAt TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_urlId ON documents(urlId)")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `documents_fts` USING FTS4(`title` TEXT NOT NULL, `text` TEXT NOT NULL, content=`documents`)",
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_documents_fts_BEFORE_UPDATE BEFORE UPDATE ON `documents` BEGIN DELETE FROM `documents_fts` WHERE `docid`=OLD.`rowid`; END",
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_documents_fts_BEFORE_DELETE BEFORE DELETE ON `documents` BEGIN DELETE FROM `documents_fts` WHERE `docid`=OLD.`rowid`; END",
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_documents_fts_AFTER_UPDATE AFTER UPDATE ON `documents` BEGIN INSERT INTO `documents_fts`(`docid`, `title`, `text`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`text`); END",
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_documents_fts_AFTER_INSERT AFTER INSERT ON `documents` BEGIN INSERT INTO `documents_fts`(`docid`, `title`, `text`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`text`); END",
                )
                db.execSQL("INSERT INTO `documents_fts`(`documents_fts`) VALUES('rebuild')")
            }
        }

        fun getInstance(context: Context): OutlineDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OutlineDatabase::class.java,
                    "outline_notes.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
