package com.aiclipcreator.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(video: VideoEntity)

    @Query("SELECT * FROM videos ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun byId(id: String): VideoEntity?

    @Query("SELECT COUNT(*) FROM videos")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM videos WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: JobEntity)

    @Query("SELECT * FROM jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun byId(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun observe(id: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE state IN ('QUEUED','RUNNING','PAUSED') ORDER BY priority DESC, createdAt ASC")
    fun observeActive(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE state = 'QUEUED' ORDER BY priority DESC, createdAt ASC LIMIT 1")
    suspend fun nextQueued(): JobEntity?

    @Query("SELECT * FROM jobs WHERE state IN ('RUNNING','QUEUED','PAUSED') ORDER BY priority DESC, createdAt ASC LIMIT 1")
    suspend fun nextActive(): JobEntity?

    @Query("SELECT * FROM jobs WHERE state = 'RUNNING'")
    suspend fun running(): List<JobEntity>

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM jobs WHERE state = 'COMPLETED'")
    fun observeCompletedCount(): Flow<Int>
}

@Dao
interface CheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: CheckpointEntity)

    @Query("SELECT * FROM checkpoints WHERE jobId = :jobId")
    suspend fun byJob(jobId: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoints")
    suspend fun all(): List<CheckpointEntity>

    @Query("DELETE FROM checkpoints WHERE jobId = :jobId")
    suspend fun delete(jobId: String)
}

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TranscriptEntity>)

    @Query("SELECT * FROM transcripts WHERE jobId = :jobId ORDER BY startMs ASC")
    suspend fun byJob(jobId: String): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE jobId = :jobId ORDER BY startMs ASC")
    fun observe(jobId: String): Flow<List<TranscriptEntity>>

    @Query("DELETE FROM transcripts WHERE jobId = :jobId")
    suspend fun delete(jobId: String)
}

@Dao
interface CandidateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CandidateEntity>)

    @Query("SELECT * FROM candidates WHERE jobId = :jobId ORDER BY engagement DESC")
    suspend fun byJob(jobId: String): List<CandidateEntity>

    @Query("SELECT * FROM candidates WHERE jobId = :jobId ORDER BY engagement DESC")
    fun observe(jobId: String): Flow<List<CandidateEntity>>

    @Query("SELECT * FROM candidates ORDER BY engagement DESC LIMIT :limit")
    fun observeTop(limit: Int = 100): Flow<List<CandidateEntity>>

    @Query("DELETE FROM candidates WHERE jobId = :jobId")
    suspend fun delete(jobId: String)
}

@Dao
interface ClipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(clip: ClipEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(clips: List<ClipEntity>)

    @Query("SELECT * FROM clips ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE jobId = :jobId ORDER BY startMs ASC")
    fun observeByJob(jobId: String): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE jobId = :jobId ORDER BY startMs ASC")
    suspend fun byJob(jobId: String): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun byId(id: String): ClipEntity?

    @Query("SELECT * FROM clips WHERE quality = :quality ORDER BY createdAt DESC")
    fun observeByQuality(quality: String): Flow<List<ClipEntity>>

    @Query("SELECT COUNT(*) FROM clips")
    fun observeCount(): Flow<Int>

    @Query("UPDATE clips SET galleryUri = :uri, exportedAt = :at WHERE id = :id")
    suspend fun markExported(id: String, uri: String, at: Long)

    @Query("UPDATE clips SET title = :title, description = :description, hashtagsJson = :tags, srt = :srt WHERE id = :id")
    suspend fun updateText(id: String, title: String, description: String, tags: String, srt: String)

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Query("SELECT * FROM models")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models")
    suspend fun all(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun byId(id: String): ModelEntity?

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ExportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(export: ExportEntity)

    @Query("SELECT * FROM exports ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ExportEntity>>

    @Query("DELETE FROM exports WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface LiveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stream: LiveEntity)

    @Query("SELECT * FROM live_streams ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<LiveEntity>>

    @Query("SELECT * FROM live_streams WHERE id = :id")
    suspend fun byId(id: String): LiveEntity?

    @Query("DELETE FROM live_streams WHERE id = :id")
    suspend fun delete(id: String)
}
