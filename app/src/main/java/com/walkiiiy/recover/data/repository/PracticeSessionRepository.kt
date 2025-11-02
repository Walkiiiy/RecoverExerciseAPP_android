package com.walkiiiy.recover.data.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import com.walkiiiy.recover.data.db.AppDatabase
import com.walkiiiy.recover.data.db.PracticeSessionEntity
import java.util.concurrent.Executors

class PracticeSessionRepository private constructor(context: Context) {

    private val dao = AppDatabase.getInstance(context).practiceSessionDao()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun insertSession(session: PracticeSessionEntity, onComplete: (() -> Unit)? = null) {
        ioExecutor.execute {
            try {
                dao.insertSession(session)
                // 确保回调在主线程执行
                onComplete?.let {
                    mainHandler.post(it)
                }
            } catch (e: Exception) {
                // 忽略数据库异常，防止崩溃
            }
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
