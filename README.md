# **CloudPlayPlus**

A lightweight, fast, and minimal Android launcher for **Xbox Cloud Gaming**.  
CloudPlayPlus focuses on simplicity, performance, and a clean experience—ideal for handhelds like the **Logitech G Cloud**, Android tablets, and phones.

---

## 🚀 Features

- **Instant Launch** — Opens Xbox Cloud Gaming quickly without extra UI clutter.
- **AMD FidelityFX CAS** — CloudPlayPlus applies AMD's Contrast Adaptive Sharpening (CAS) algorithm via a WebGL2 fragment shader to make Xbox Cloud Gaming streams clearer. CAS reduces the soft, blurred look of video-based streaming and improves text and UI crispness without adding latency. The shader uses luma-weighted sharpening (suppressing sharpening in dark areas to avoid noise amplification) and hooks into `requestVideoFrameCallback` for frame-accurate rendering. Runs with a `low-power` WebGL context to preserve battery life.
- **Discord Overlay** — A toggle in the Xbox guide enables Discord mode. When active, 4 rapid taps opens a Discord window over the stream (works on the dashboard and mid-stream); 4 more taps closes it. Taps inside the Discord window itself don't count, so you can use it normally. When the toggle is off, 4 taps does nothing and Discord adds zero overhead — the WebView is never even created.
- **Notes** — A built-in notepad opened from the Xbox guide, for keeping track of puzzle codes, quest steps, or anything else mid-game. Notes save automatically and persist locally on the device.
- **Lightweight** — Built with Kotlin and minimal dependencies, <800kb app size>.  
- **Android‑Native** — Uses modern Android tooling (Gradle Kotlin DSL, AndroidX).  
- **Open Source** — Simple codebase designed for learning, modding, and extending.

---

## 📦 Installation

### **Option 1: Build From Source**
1. Clone the repo:
   ```bash
   git clone https://github.com/AlexManzi/CloudPlayPlus.git
   ```
2. Open the project in **Android Studio**.
3. Let Gradle sync.
4. Build & run on your device:
   - **Run → Run 'app'**
   - or use:
     ```bash
     ./gradlew assembleDebug
     ```

### **Option 2: Install APK**
Download the latest APK from the **[Releases](https://github.com/AlexManzi/CloudPlayPlus/releases)** tab and sideload it onto your device.

---

## 🧱 Project Structure

```
CloudPlayPlus/
 ├── app/                 # Main Android app module
 ├── gradle/              # Gradle wrapper files
 ├── build.gradle.kts     # Root build config
 ├── settings.gradle.kts  # Project settings
 ├── gradle.properties    # Build properties
 └── README.md
```

---

## 🛠️ Tech Stack

| Component | Details |
|----------|---------|
| Language | Kotlin |
| Build System | Gradle (Kotlin DSL) |
| Target | Android 8.0+ |
| UI | Native Android Views (lightweight) |

---


## 🤝 Contributing

Contributions are welcome!  
Feel free to open issues, submit PRs, or propose features.

---

## 📄 License

MIT License — free to use, modify, and distribute.

---

## 🙌 Acknowledgments

CloudPlayPlus is inspired by the desire for a **clean, fast, handheld‑friendly** way to launch Xbox Cloud Gaming on Android devices.

---