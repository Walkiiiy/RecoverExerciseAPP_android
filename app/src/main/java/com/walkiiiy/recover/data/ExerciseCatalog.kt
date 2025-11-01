package com.walkiiiy.recover.data

import com.walkiiiy.recover.data.model.Exercise

object ExerciseCatalog {
    val exercises: List<Exercise> = listOf(
        Exercise(
            id = "lymph_drainage",
            title = "淋巴引流操",
            description = "促进腋窝淋巴回流，减轻术后手臂水肿。",
            demoVideoAssetName = "demo_breast_rehab",
            repetitionCount = 10,
        ),
        Exercise(
            id = "shoulder_mobility",
            title = "肩关节灵活操",
            description = "改善肩关节活动度，缓解肩部紧张。",
            demoVideoAssetName = "demo_breast_rehab",
            repetitionCount = 12,
        ),
        Exercise(
            id = "chest_expansion",
            title = "胸廓扩展操",
            description = "提升胸廓扩展能力，辅助呼吸功能恢复。",
            demoVideoAssetName = "demo_breast_rehab",
            repetitionCount = 8,
        ),
    )
}
