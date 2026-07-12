package com.tarteelcompanion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.tarteelcompanion.data.dao.ImportDao
import com.tarteelcompanion.data.dao.OccurrenceDao
import com.tarteelcompanion.data.dao.ReviewLogDao
import com.tarteelcompanion.data.dao.SpotDao
import com.tarteelcompanion.data.entity.ImportScreenshotEntity
import com.tarteelcompanion.data.entity.OccurrenceEntity
import com.tarteelcompanion.data.entity.ReviewLogEntity
import com.tarteelcompanion.data.entity.SpotEntity
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.data.model.ReviewGrade
import com.tarteelcompanion.data.model.ReviewKind
import com.tarteelcompanion.data.model.SpotState
import com.tarteelcompanion.mnemonics.MnemonicDao
import com.tarteelcompanion.mnemonics.MnemonicEntity
import com.tarteelcompanion.mnemonics.MnemonicSource
import com.tarteelcompanion.mnemonics.MnemonicStatus

class Converters {
    @TypeConverter fun mnemonicStatusToString(v: MnemonicStatus): String = v.name
    @TypeConverter fun stringToMnemonicStatus(v: String): MnemonicStatus = MnemonicStatus.valueOf(v)
    @TypeConverter fun mnemonicSourceToString(v: MnemonicSource): String = v.name
    @TypeConverter fun stringToMnemonicSource(v: String): MnemonicSource = MnemonicSource.valueOf(v)
    @TypeConverter fun spotStateToString(v: SpotState): String = v.name
    @TypeConverter fun stringToSpotState(v: String): SpotState = SpotState.valueOf(v)
    @TypeConverter fun mistakeTypeToString(v: MistakeType): String = v.name
    @TypeConverter fun stringToMistakeType(v: String): MistakeType = MistakeType.valueOf(v)
    @TypeConverter fun reviewKindToString(v: ReviewKind): String = v.name
    @TypeConverter fun stringToReviewKind(v: String): ReviewKind = ReviewKind.valueOf(v)
    @TypeConverter fun reviewGradeToString(v: ReviewGrade): String = v.name
    @TypeConverter fun stringToReviewGrade(v: String): ReviewGrade = ReviewGrade.valueOf(v)
}

@Database(
    entities = [
        SpotEntity::class,
        OccurrenceEntity::class,
        ImportScreenshotEntity::class,
        ReviewLogEntity::class,
        MnemonicEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spotDao(): SpotDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun importDao(): ImportDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun mnemonicDao(): MnemonicDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tarteel-companion.db",
            ).build().also { instance = it }
        }
    }
}
