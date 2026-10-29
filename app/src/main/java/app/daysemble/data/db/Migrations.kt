package app.daysemble.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations. Each one is additive where possible (no table rebuilds), so existing rows,
 * their ids and their completion state are kept as they are. The exported schemas in app/schemas
 * are the reference; MigrationTest checks each step against them.
 */
object Migrations {
    /**
     * v2: repeating tasks and per-task countdowns.
     * - task: countdownEnabled (default off), seriesId and occurrenceDate (null for every existing
     *   task, which stays a one-off), and a unique (seriesId, occurrenceDate) index.
     * - task_series: rule and template of a repeating task.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `task` ADD COLUMN `countdownEnabled` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `task` ADD COLUMN `seriesId` TEXT")
            db.execSQL("ALTER TABLE `task` ADD COLUMN `occurrenceDate` INTEGER")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_task_seriesId_occurrenceDate` ON `task` (`seriesId`, `occurrenceDate`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `task_series` (`id` TEXT NOT NULL, `listId` TEXT NOT NULL, `rrule` TEXT NOT NULL, " +
                    "`anchorDate` INTEGER NOT NULL, `title` TEXT NOT NULL, `notes` TEXT NOT NULL, `dueTimeMinutes` INTEGER, " +
                    "`priority` INTEGER NOT NULL, `countdownEnabled` INTEGER NOT NULL DEFAULT 0, `reminders` TEXT NOT NULL DEFAULT '', " +
                    "`endedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`listId`) REFERENCES `task_list`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_series_listId` ON `task_series` (`listId`)")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2)
}
