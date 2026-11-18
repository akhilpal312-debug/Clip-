package com.aiclipcreator.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VideoEntity::class,
        JobEntity::class,
        CheckpointEntity::class,
        TranscriptEntity::class,
        CandidateEntity::class,
        ClipEntity::class,
        ModelEntity::class,
        ExportEntity::class,
        LiveEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AiClipDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun jobDao(): JobDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun candidateDao(): CandidateDao
    abstract fun clipDao(): ClipDao
    abstract fun modelDao(): ModelDao
    abstract fun exportDao(): ExportDao
    abstract fun liveDao(): LiveDao

    companion object {
        @Volatile
        private var instance: AiClipDatabase? = null

        fun get(context: Context): AiClipDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AiClipDatabase::class.java,
                "ai_clip_creator.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
