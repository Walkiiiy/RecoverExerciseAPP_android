package com.walkiiiy.recover.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "practice_sessions")
data class PracticeSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val exerciseId: String,
    val exerciseTitle: String,
    val exerciseDescription: String,
    val score: Double,
    val recordedVideoPath: String,
    val createdAtMillis: Long,
)
