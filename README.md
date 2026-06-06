# MIRO — Real-time Object Detection Camera

MIRO is an Android application built with Jetpack Compose and CameraX that performs real-time object detection using TensorFlow Lite. It supports multiple models, video recording with bounding box overlay, automatic frame capture, and customizable detection settings.

## Features

- **Real-time Detection** — CameraX preview + TFLite GPU/NNAPI/CPU acceleration, running at 30+ FPS
- **Model Selection** — Switch between different TFLite models (COCO, Fall Detection, Fire Detection, Pose)
- **Bounding Box Overlay** — Green bounding boxes with class labels and confidence scores rendered on the preview
- **Pose Skeleton** — 17-keypoint COCO skeleton overlay for pose estimation models
- **Video Recording** — Standard CameraX recording. **Bounding box overlay recording** via custom MediaCodec encoder for detection-box-embedded video
- **Frame Capture** — Save detection frames to private storage (JPEG + metadata)
- **Auto-Save** — Automatically capture frames when a specified class is detected (configurable, stabilization built-in)
- **Record Management** — Browse saved images/videos with pagination, full-screen preview, video playback with seekbar, share to other apps
- **Quick Delete** — Swipe up on a card to delete (toggle mode on/off)
- **Settings** — Confidence/IoU thresholds, theme toggle (dark/light), model import/delete/rename
- **Flashlight & Camera Switch** — Torch toggle and front/back camera switching
- **Tap to Focus & Pinch Zoom** — Touch-to-focus and zoom gestures

## Screenshots

| Preview | Records | Settings |
|---------|---------|----------|
| (screenshots) | | |

*(Add screenshots to a `screenshots/` directory)*

## Tech Stack

| Component | Library |
|-----------|---------|
| UI | Jetpack Compose + Material 3 |
| Camera | CameraX (Preview, ImageAnalysis, VideoCapture) |
| ML Inference | TensorFlow Lite 2.16.1 (GPU delegate + NNAPI fallback) |
| Architecture | MVVM (AndroidViewModel + StateFlow) |
| Video Encoding | MediaCodec + MediaMuxer (for overlay recording) |
| File Storage | App-private directory (`filesDir/MIRO/`) |

## Models

MIRO expects TFLite models with the following output formats:

| Model Type | Output Shape | Description |
|-----------|-------------|-------------|
| Standard Detection | `[1, 300, 6]` | NMS-processed: x1,y1,x2,y2,conf,cls (YOLO26n) |
| Raw YOLO | `[1, 8, 11109]` | Raw grid output decoded in-app (YOLO11n) |
| Pose | `[1, 56, 8400]` | 17 keypoints × 3 (x,y,vis) + bbox + score |

Models are bundled in `app/src/main/assets/model/`. You can import custom models via the Settings page.

### Included label files
- `coco_classes.txt` — 80-class COCO dataset labels
- `fall_classes.txt` — Fall detection labels
- `fire_classes.txt` — Fire/smoke detection labels
- `pose_classes.txt` — Pose estimation labels

## Building

**Prerequisites:**
- Android Studio Hedgehog (2023.1.1+) or compatible IDE
- JDK 17
- Android SDK 34
- Android device (API 26+) or emulator

**Build:**
```bash
git clone https://github.com/YOUR_USERNAME/miro.git
cd miro
./gradlew assembleDebug
```

Install via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project Structure

```
app/src/main/java/com/n0va/detection/
├── MainActivity.kt           — Entry point, camera lifecycle
├── MainViewModel.kt          — State management, inference loop, recording
├── camera/
│   ├── CameraManager.kt      — CameraX lifecycle, focus, flash, recording
│   └── OverlayVideoRecorder.kt — Custom MediaCodec encoder with box overlay
├── detection/
│   ├── TFLiteDetector.kt     — Model loading, inference, NMS, post-processing
│   ├── DetectionResult.kt    — Data classes: DetectionResult, KeyPoint, PoseConstants
│   └── DetectionEngine.kt    — Engine abstraction interface
├── ui/
│   ├── MainScreen.kt         — Tab navigation scaffold
│   ├── camera/
│   │   └── CameraPreviewTab.kt — Camera preview, overlays, controls
│   ├── records/
│   │   └── RecordsTab.kt     — Saved images/videos browser, preview, delete
│   ├── file/
│   │   └── FilePreviewTab.kt — External file detection
│   ├── settings/
│   │   └── SettingsTab.kt    — Model management, thresholds, recording settings
│   ├── components/
│   │   ├── DetectionControlBar.kt — Start/stop detection, record, save controls
│   │   ├── PreviewFrame.kt       — Camera preview with box overlay
│   │   ├── PageHeader.kt         — Shared page header
│   │   ├── LogPanel.kt           — Detection log display
│   │   └── BoxOverlayView.kt     — Android View for bounding box + skeleton rendering
│   └── theme/
│       └── Theme.kt          — Dark/Light color schemes
```

## Configuration

Settings are persisted in `SharedPreferences` (`miro_settings`):

| Key | Default | Description |
|-----|---------|-------------|
| `conf_threshold` | 0.25 | Detection confidence threshold |
| `iou_threshold` | 0.45 | NMS IoU threshold |
| `dark_theme` | true | Dark/Light theme |
| `draw_boxes_on_recording` | true | Overlay boxes on recorded video |
| `auto_save_enabled` | false | Auto-capture when class detected |
| `auto_save_class` | "" | Target class name for auto-capture |

## Custom Model Import

1. Go to **Settings** → **Model Selection**
2. Tap **＋ 导入模型** (Import Model)
3. Select a `.tflite` file
4. Configure: input size, class count, pose mode
5. Edit class labels if needed

You can also delete or rename imported models from the settings page.

## Data Privacy

- All saved frames and recordings are stored in the app's private directory (`filesDir/MIRO/`)
- No data is uploaded to any server
- MediaStore is not used for storage; your gallery remains unaffected
- Camera permission is required only for detection

## License

MIRO is released under a custom **Academic Non-Commercial License**.

This software is provided for **learning, research, and personal study purposes only**.

Commercial use, resale, or distribution of this software or its derivatives for a fee is **strictly prohibited**.

See [LICENSE](LICENSE) for full terms.

---

**Author:** Mcriel_CHYJIE  
**Contact:** 2713150993@qq.com  
**Version:** 0.2.8
