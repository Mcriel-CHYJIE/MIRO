<div align="center">

# MIRO

**Real-time Object Detection Camera · 实时目标检测相机**

*An Android application powered by CameraX, TensorFlow Lite, and Jetpack Compose*

*基于 CameraX、TensorFlow Lite 和 Jetpack Compose 的 Android 应用*

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.0-blue?logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Compose-2024.02-green?logo=jetpackcompose" alt="Compose"/>
  <img src="https://img.shields.io/badge/TFLite-2.16-orange?logo=tensorflow" alt="TFLite"/>
  <img src="https://img.shields.io/badge/CameraX-1.3-purple" alt="CameraX"/>
  <img src="https://img.shields.io/badge/API-26%2B-brightgreen" alt="minSdk"/>
  <img src="https://img.shields.io/badge/license-Non--Commercial-red" alt="License"/>
</p>

</div>

---

## Overview · 概述

**MIRO** is a real-time object detection Android application that performs on-device inference using TensorFlow Lite with GPU/NNAPI acceleration. It supports multi-model switching, video recording with bounding box overlay, automatic frame capture by class, and a full-featured record management system.

**MIRO** 是一款实时目标检测 Android 应用，使用 TensorFlow Lite 在设备端进行推理，支持 GPU/NNAPI 加速。支持多模型切换、检测框叠加录像、按类别自动保存检测帧以及完整的记录管理系统。

## Features · 功能特性

| Feature | Description |
|---------|-------------|
| 🎯 **Real-time Detection** | 30+ FPS on-device inference with TFLite GPU delegate |
| 📷 **Camera** | CameraX preview, tap-to-focus, pinch-to-zoom, torch |
| 🔄 **Multi-Model** | Switch between COCO, pose, fall/fire detection models |
| 📹 **Overlay Recording** | Save video with bounding boxes via custom MediaCodec encoder |
| 📸 **Frame Capture** | Save detection frames with boxes and metadata |
| 🤖 **Auto-Save** | Auto-capture when a specific class is detected (stabilized) |
| 🗂️ **Record Management** | Browse, filter, paginate, full-screen preview with video playback |
| 🎨 **Theme** | Dark/Light mode, customizable thresholds |
| 📦 **Model Import** | Import custom TFLite models in-app |

## Screenshots · 截图

> *(Add screenshots to a `screenshots/` directory)*

| Live Detection | Records | Settings |
|:---:|:---:|:---:|
| ![](screenshots/preview.png) | ![](screenshots/records.png) | ![](screenshots/settings.png) |

## Architecture · 项目架构

```
app/src/main/java/com/n0va/detection/
├── MainActivity.kt          # Entry point, permission handling, camera wiring
├── MainViewModel.kt         # MVVM ViewModel — inference loop, recording, state, persistence
├── camera/
│   ├── CameraManager.kt     # CameraX lifecycle, focus, flash, dual recording modes
│   └── OverlayVideoRecorder.kt  # Custom MediaCodec encoder with detection box overlay
├── detection/
│   ├── TFLiteDetector.kt    # Model loading, inference, NMS, multi-format decoding
│   ├── DetectionResult.kt   # Data models: DetectionResult, KeyPoint, PoseConstants
│   └── DetectionEngine.kt   # Engine abstraction (legacy NCNN interface)
├── ui/
│   ├── MainScreen.kt        # Tab navigation with bottom bar
│   ├── camera/
│   │   └── CameraPreviewTab.kt   # Preview, overlays, recording indicator
│   ├── records/
│   │   └── RecordsTab.kt         # Record browser, video player, swipe-to-delete
│   ├── file/
│   │   └── FilePreviewTab.kt     # External file detection
│   ├── settings/
│   │   └── SettingsTab.kt        # Model/recording/save settings
│   ├── components/
│   │   ├── DetectionControlBar.kt  # Start/stop, record, save controls
│   │   ├── PreviewFrame.kt        # Camera preview with overlay composable
│   │   ├── PageHeader.kt          # Shared page header
│   │   ├── LogPanel.kt            # Detection event log
│   │   └── BoxOverlayView.kt      # Android View for box + skeleton rendering
│   └── theme/
│       └── Theme.kt               # Dark/Light color scheme
```

## Tech Stack · 技术栈

| Layer | Technology |
|-------|-----------|
| **Language** | Kotlin 2.0 |
| **UI** | Jetpack Compose + Material 3 |
| **Camera** | CameraX (Preview + ImageAnalysis + VideoCapture) |
| **ML Inference** | TensorFlow Lite 2.16 (GPU Delegate / NNAPI / CPU) |
| **Architecture** | MVVM — AndroidViewModel + StateFlow |
| **Video Encoding** | MediaCodec + MediaMuxer (overlay recording) |
| **Storage** | App-private directory (`filesDir/MIRO/`) |
| **Build** | Gradle 8.2 + JDK 17 |

## Quick Start · 快速开始

### Prerequisites · 前置要求

- Android Studio Hedgehog (2023.1.1+) or IntelliJ IDEA
- JDK 17
- Android SDK 34
- Android device running API 26+

### Build · 构建

```bash
git clone https://github.com/Mcriel-CHYJIE/MIRO.git
cd MIRO
./gradlew assembleDebug
```

### Install · 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Model Format · 模型格式

MIRO supports TFLite models in the following output formats:

| Type | Output Tensor | Description |
|------|--------------|-------------|
| **NMS-processed** | `[1, 300, 6]` | x1,y1,x2,y2,conf,cls — YOLO26n output |
| **Raw YOLO** | `[1, 8, 11109]` | Grid-based, decoded + NMS in-app |
| **Pose** | `[1, 56, 8400]` | Bbox + score + 17 keypoints × 3 |

Built-in label files: `coco_classes.txt` (80 classes), `pose_classes.txt`.

### Import Custom Model

1. Go to **Settings** → tap **＋ Import Model**
2. Select a `.tflite` file
3. Configure input size, class count, pose mode
4. Edit class labels

## Configuration · 配置

Settings are persisted in `SharedPreferences` (`miro_settings`):

| Key | Default | Description |
|-----|---------|-------------|
| `conf_threshold` | 0.25 | Detection confidence threshold |
| `iou_threshold` | 0.45 | NMS IoU threshold |
| `dark_theme` | true | Dark/Light theme |
| `draw_boxes_on_recording` | true | Overlay boxes on recorded video |
| `auto_save_enabled` | false | Auto-capture when target class detected |
| `auto_save_class` | "" | Target class name for auto-capture |

## Privacy · 隐私

- All saved frames and recordings are stored in **app-private directory** (`filesDir/MIRO/`)
- **No data is uploaded** to any server
- **MediaStore is not used** — your gallery remains unaffected
- Camera permission is required only for detection

## License · 许可

**MIRO — Academic Non-Commercial License**

This software is provided for **learning, research, and personal study purposes only**.

**Commercial use, resale, redistribution for profit, or inclusion in commercial products is strictly prohibited.**

See [LICENSE](LICENSE) for full terms.

---

<div align="center">

**Made with ❤️ by [Mcriel_CHYJIE](https://github.com/Mcriel-CHYJIE)**

**Contact: 2713150993@qq.com**

v0.2.8

</div>
