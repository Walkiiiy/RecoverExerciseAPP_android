# 乳腺癌康复操 Android 应用

这是一个使用 Kotlin 编写的原生 Android 应用，提供乳腺癌术后康复操的跟练与历史记录功能。主界面支持选择康复操，练习界面在播放示范视频的同时调用摄像头录制用户动作，并通过内置的 TFLite 模型给出评分，同时将评分与录制视频保存到本地历史记录中。

## 环境搭建

1. **开发工具**
   - Android Studio Giraffe (或更高版本)，内置 JDK 17。
   - Android SDK 34。
2. **克隆项目并导入**
   ```bash
   git clone <your-repo-url>
   cd RecoverExerciseAPP
   ```
   使用 Android Studio 选择 *Open* 导入项目，首次打开会自动完成 Gradle 同步。
3. **Gradle Wrapper**
   - 项目根目录未附带 `gradlew`，请在本地安装好 Gradle 后执行一次：
     ```bash
     gradle wrapper 
     ```
   - 之后即可使用 `./gradlew assembleDebug` 或直接在 Android Studio 中运行。
4. **运行到设备**
   - 需要 Android 7.0 (API 24) 以上真机或模拟器。
   - 运行前请授予摄像头与麦克风权限。

## 关键目录结构

```
app/
├── src/main/
│   ├── java/com/walkiiiy/recover/
│   │   ├── data/                    # 演示数据、Room 数据库、TFLite 评分器
│   │   ├── ui/                      # Activity / Fragment / ViewModel
│   │   └── ...
│   ├── res/                         # 布局、字符串、主题、菜单等资源
│   ├── AndroidManifest.xml
│   └── assets/practice_quality_model.tflite
└── build.gradle.kts
```

## 功能说明

- **康复操选择**：在主界面选择康复动作，查看简介并进入练习。
- **练习跟练**：
  - 演示视频（`res/raw/demo_breast_rehab.mp4`）播放时同步调用 CameraX 录制用户动作。
  - 录制完成后在后台线程运行 TFLite 模型评分，结果写入本地数据库。
- **历史记录**：展示练习列表、时间、得分，并可通过 `FileProvider` 播放已录制的视频。

## 模型与媒体资源

- `app/src/main/assets/practice_quality_model.tflite` 为占位文件，请替换为真实的 TensorFlow Lite 模型。
- `app/src/main/res/raw/demo_breast_rehab.mp4` 为示例占位文件，请替换为真实的康复操示范视频。
- 如需增加更多康复操，请编辑 `ExerciseCatalog` 并为每个动作提供对应的演示视频资源。

## 构建与部署

1. **准备资产文件**：将训练好的 TFLite 模型（例如 `pose_landmarker_full.task`、`practice_quality_model.tflite`）和演示视频替换到 `app/src/main/assets/` 与 `app/src/main/res/raw/` 对应位置。
2. **使用 Gradle 构建**：
   ```bash
   ./gradlew clean assembleDebug
   ```
   生成的调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
3. **部署到设备**：连接真机或启动模拟器，使用 Android Studio 直接运行，或在命令行执行：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. **发布版本**：通过 `Build > Generate Signed Bundle / APK…` 或运行 `./gradlew assembleRelease`，并使用自己的签名配置。

## 权限与存储

- 应用在运行时申请摄像头与麦克风权限，Android 9 及以下还需写存储权限。
- 练习视频存储在 `Android/data/<package>/files/Movies/practice_sessions/` 中，并通过 `FileProvider` 分享给媒体播放器。

## 后续可扩展点

1. 集成真实的姿态分析 / 动作识别模型，替换当前的启发式评分逻辑。
2. 为历史记录添加分页、搜索与视频回放界面。
3. 增加更多康复动作、分阶段训练计划与推送提醒。
4. 使用 WorkManager 上传练习数据到云端，便于医生远程评估。
5. 使用 Hilt/DI 与 Kotlin 协程进一步优化架构。

所有代码均为示例参考，实际产品化时请针对安全性、模型精度、数据隐私等方面做进一步完善。*** End Patch
