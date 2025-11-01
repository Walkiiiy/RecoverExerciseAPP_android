package com.walkiiiy.recover.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.walkiiiy.recover.data.db.AppDatabase
import com.walkiiiy.recover.data.db.PracticeSessionEntity
import java.util.concurrent.Executors

class PracticeSessionRepository private constructor(context: Context) {

    private val dao = AppDatabase.getInstance(context).practiceSessionDao()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun insertSession(session: PracticeSessionEntity, onComplete: (() -> Unit)? = null) {
        ioExecutor.execute {
            dao.insertSession(session)
            onComplete?.invoke()
        }
    }

    fun observeSessions(): LiveData<List<PracticeSessionEntity>> = dao.observeSessions()

    companion object {
        @Volatile
        private var INSTANCE: PracticeSessionRepository? = null

        fun getInstance(context: Context): PracticeSessionRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PracticeSessionRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
