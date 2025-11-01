package com.walkiiiy.recover.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PracticeSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: PracticeSessionEntity)

    @Query("SELECT * FROM practice_sessions ORDER BY createdAtMillis DESC")
    fun observeSessions(): LiveData<List<PracticeSessionEntity>>
}
