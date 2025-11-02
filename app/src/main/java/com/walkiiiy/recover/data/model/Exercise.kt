package com.walkiiiy.recover.data.model

data class Exercise(
    val id: String,
    val title: String,
    val description: String,
    val demoVideoAssetName: String,
    val repetitionCount: Int,
    val thumbnailDrawable: String = "exercises", // 封面图片drawable名称
)
