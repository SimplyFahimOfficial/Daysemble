package app.daysemble.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun sourceKind(v: String) = SourceKind.valueOf(v)
    @TypeConverter fun sourceKind(v: SourceKind) = v.name
    @TypeConverter fun syncState(v: String) = SyncState.valueOf(v)
    @TypeConverter fun syncState(v: SyncState) = v.name
    @TypeConverter fun ownerType(v: String) = OwnerType.valueOf(v)
    @TypeConverter fun ownerType(v: OwnerType) = v.name
}

@Database(
    entities = [
        CalendarSourceEntity::class, EventEntity::class, TaskListEntity::class, TaskEntity::class,
        ReminderEntity::class, ScheduledAlarmEntity::class, DeliveredReminderEntity::class,
        TaskSeriesEntity::class, TaskOccurrenceSkipEntity::class, SleepSessionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DaysembleDatabase : RoomDatabase() {
    abstract fun calendars(): CalendarSourceDao
    abstract fun events(): EventDao
    abstract fun taskLists(): TaskListDao
    abstract fun tasks(): TaskDao
    abstract fun taskSeries(): TaskSeriesDao
    abstract fun reminders(): ReminderDao
    abstract fun alarms(): AlarmDao
    abstract fun skips(): TaskOccurrenceSkipDao
    abstract fun sleep(): SleepDao
    abstract fun backup(): BackupDao
    abstract fun backupImport(): BackupImportDao

    companion object {
        const val NAME = "daysemble.db"

        fun build(context: Context): DaysembleDatabase =
            Room.databaseBuilder(context, DaysembleDatabase::class.java, NAME)
                // Migrations are added per schema version. No destructive fallback, so data is never silently dropped.
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
