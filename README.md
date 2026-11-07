# 六花声库（Rikka Voice）

独立的 Android 日语语音引擎 App，和「六花日语」是两个 App。

- 包名：`com.rikka.voice`，minSdk 26，target/compile 35，arm64-v8a
- 本机引擎：VOICEVOX CORE / Supertonic 3（sherpa-onnx）；电脑引擎：AivisSpeech / VOICEVOX / Style-Bert-VITS2 / GPT-SoVITS
- 对外提供两种用法：注册为系统文字转语音（TTS）引擎；在 `127.0.0.1:50021` 提供 VOICEVOX 兼容 HTTP 接口（六花日语可直接选它朗读）
- 构建细节见 `构建说明.md`；引擎开关在 `gradle.properties`
- 发布签名：仓库根目录 `rikka-voice.p12`（alias `rikka-voice`，口令见 app/build.gradle.kts 环境变量默认值），首版即固定，后续版本可直接覆盖安装

APK 在本仓库的 Releases 里下载。
