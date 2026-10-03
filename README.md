# 低频噪音侦测（Android）

手机端低频噪音初筛 App：实时频谱 + 瀑布图，自动推断声源类型（变压器、水泵、变频设备、间歇设备、宽频噪声），并通过引导巡测比较各位置强弱、推断大致方位。

## 模块

| 模块 | 内容 |
| --- | --- |
| `core/` | 纯 Kotlin，无 Android 依赖：降采样（48 kHz → 2 kHz）、FFT 功率谱、峰值与谐波识别、频率跟踪、推断规则库、巡测分析。全部有单元测试。 |
| `app/` | Android（Compose）：麦克风采集（优先 `UNPROCESSED` 音源）、识别页、巡测页。 |

核心流程在 `core/.../analysis/LiveAnalyzer.kt`；推断规则在 `core/.../inference/SourceInference.kt`，阈值均为初版经验值，需要用实测案例校正。

## 构建

需要 JDK 17+（可用 Android Studio 自带的 `jbr`）和 Android SDK（`local.properties` 中的 `sdk.dir`）。

```sh
./gradlew :core:test          # 单元测试（合成信号）
./gradlew :app:assembleDebug  # 输出 app/build/outputs/apk/debug/app-debug.apk
```

## 已知限制

- 手机麦克风未校准，声级为相对值（dBFS），只用于同一部手机在各位置之间比较。
- 30 Hz 以下手机麦克风灵敏度很低；不支持 `UNPROCESSED` 音源的机型可能滤掉部分低频。
- 一部手机无法测方向，方位来自多位置强度对比，受房间驻波影响，每个位置需要多测几个点。
