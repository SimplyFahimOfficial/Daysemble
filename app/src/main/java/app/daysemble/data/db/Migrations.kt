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

    /**
     * v3: task durations, skipped-occurrence history and manual sleep records. Additive only.
     * - task, task_series: durationMinutes (null for every existing row: no task takes free time
     *   it didn't take before).
     * - task_occurrence_skip: dates of a repeating task that were skipped.
     * - sleep_session: "I'm going to sleep" / "I just woke up" records.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `task` ADD COLUMN `durationMinutes` INTEGER")
            db.execSQL("ALTER TABLE `task_series` ADD COLUMN `durationMinutes` INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `task_occurrence_skip` (`seriesId` TEXT NOT NULL, `occurrenceDate` INTEGER NOT NULL, " +
                    "`skippedAt` INTEGER NOT NULL, PRIMARY KEY(`seriesId`, `occurrenceDate`), " +
                    "FOREIGN KEY(`seriesId`) REFERENCES `task_series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sleep_session` (`id` TEXT NOT NULL, `startUtc` INTEGER, `endUtc` INTEGER, " +
                    "`startZone` TEXT, `endZone` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sleep_session_startUtc` ON `sleep_session` (`startUtc`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sleep_session_endUtc` ON `sleep_session` (`endUtc`)")
        }
    }

    /**
     * v4: Google Calendar & Tasks sync is gone; calendars of the phone's Calendar Provider are
     * mirrored instead. No table is rebuilt (SQLite on API 26 can't drop columns), so the old sync
     * columns stay, unused.
     * - Events of Google calendars are deleted (with their reminders) and the calendars removed:
     *   the same events now arrive from the device's own calendar, and keeping both would show them twice.
     *   Alarms stay registered in scheduled_alarm so the next reminder pass cancels them.
     * - Google task lists become ordinary on-device lists with all their tasks, so no task is lost.
     *   Tasks waiting to be deleted on Google (already hidden by the user) are removed.
     * - Everything that is left is marked LOCAL, and the outbox, conflict_log and sync_state tables go.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM `event` WHERE `calendarId` IN (SELECT `id` FROM `calendar_source` WHERE `kind` = 'GOOGLE')")
            db.execSQL("DELETE FROM `calendar_source` WHERE `kind` = 'GOOGLE'")
            db.execSQL("DELETE FROM `task` WHERE `syncState` = 'PENDING_DELETE'")
            db.execSQL(
                "UPDATE `task_list` SET `kind` = 'LOCAL', `remoteId` = NULL, `accountEmail` = NULL, `isSyncEnabled` = 1, " +
                    "`lastPulledAt` = NULL WHERE `kind` = 'GOOGLE'",
            )
            db.execSQL(
                "UPDATE `task` SET `syncState` = 'LOCAL', `remoteId` = NULL, `etag` = NULL, `remotePosition` = NULL, `remoteUpdatedAt` = NULL",
            )
            db.execSQL(
                "UPDATE `event` SET `syncState` = 'LOCAL', `remoteId` = NULL, `etag` = NULL, `remoteUpdatedAt` = NULL, " +
                    "`pendingClientId` = NULL, `instancesUntil` = NULL",
            )
            db.execSQL("UPDATE `calendar_source` SET `remoteId` = NULL, `accountEmail` = NULL, `syncToken` = NULL, `accessRole` = 'owner'")
            db.execSQL("DELETE FROM `reminder` WHERE `ownerType` = 'EVENT' AND `ownerId` NOT IN (SELECT `id` FROM `event`)")
            db.execSQL("DELETE FROM `reminder` WHERE `ownerType` = 'TASK' AND `ownerId` NOT IN (SELECT `id` FROM `task`)")
            db.execSQL("DROP TABLE IF EXISTS `outbox`")
            db.execSQL("DROP TABLE IF EXISTS `conflict_log`")
            db.execSQL("DROP TABLE IF EXISTS `sync_state`")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
