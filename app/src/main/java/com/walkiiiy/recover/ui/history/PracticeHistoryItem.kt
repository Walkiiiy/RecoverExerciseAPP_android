package com.walkiiiy.recover.ui.history

data class PracticeHistoryItem(
    val id: Long,
    val exerciseTitle: String,
    val timestampText: String,
    val scoreText: String,
    val videoPath: String,
)
