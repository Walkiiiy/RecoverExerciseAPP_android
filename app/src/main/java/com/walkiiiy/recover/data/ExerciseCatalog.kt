package com.walkiiiy.recover.data

import com.walkiiiy.recover.data.model.Exercise

object ExerciseCatalog {
    val exercises: List<Exercise> = listOf(
        Exercise(
            id = "lymph_drainage",
            title = "淋巴引流操",
            description = "促进腋窝淋巴回流，减轻术后手臂水肿。",
            demoVideoAssetName = "demo_breast_rehab",  // 对应 res/raw/demo_breast_rehab.mp4
            repetitionCount = 10,
            thumbnailDrawable = "exercises"
        ),
        Exercise(
            id = "shoulder_mobility",
            title = "起始式",
            description = "改善肩关节活动度，缓解肩部紧张。",
            demoVideoAssetName = "qi",  // 对应 res/raw/起.mp4
            repetitionCount = 12,
            thumbnailDrawable = "fitness"
        ),
        Exercise(
            id = "chest_expansion",
            title = "左右野马分鬃",
            description = "提升胸廓扩展能力，辅助呼吸功能恢复。",
            demoVideoAssetName = "horse",  // 对应 res/raw/野马.mp4
            repetitionCount = 8,
            thumbnailDrawable = "fitnessleg"
        ),
        Exercise(
            id = "baduan_jin",
            title = "八段锦",
            description = "传统养生功法，强身健体。",
            demoVideoAssetName = "baduanjin",  // 对应 res/raw/八段锦.mp4
            repetitionCount = 1,
            thumbnailDrawable = "exercises"
        ),
        Exercise(
            id = "baduan_jin",
            title = "走路",
            description = "传统养生功法，强身健体。",
            demoVideoAssetName = "walking",  // 对应 res/raw/walking.mp4
            repetitionCount = 1,
            thumbnailDrawable = "exercises"
        ),
    )
}
