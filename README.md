# **CloudPlayPlus**

A lightweight Android launcher for **Xbox Cloud Gaming and Remote Play**.
CloudPlayPlus focuses on simplicity, performance, and a clean experience—ideal for handhelds like the **Logitech G Cloud**, Android tablets, and phones.

---

## 🚀 Features

- **Quick Menu** — A four-finger tap opens native controls for sharpening, Notes, Discord, and Stream Settings.
- **Launch Destination** — Choose Cloud Gaming or Remote Play for the next fresh app launch. The preference is saved; changing it leaves the current session in place.
- **AMD CAS** — Choose Off, Normal, or High sharpening from the Quick Menu. The WebGL2 shader adapts to image contrast and suppresses sharpening in dark areas. If sharpening setup fails, playback falls back to direct video.
- **Stream Settings** — Prefer IPv6 when the next stream connects, with IPv4 retained as a fallback. Open Stream Stats for resolution, frame count, connection path, and device readings.
- **Discord Overlay** — Enable Discord from the Quick Menu, then use four rapid taps to show or hide its panel. Taps inside the panel don't count toward the gesture. Hiding keeps the loaded session; turning Discord off destroys its WebView. Enabling alone creates no WebView until the panel is opened.
- **Notes** — Open the local notepad from the Quick Menu for puzzle codes, quest steps, or anything else mid-game. Notes save automatically and persist locally on the device.
- **Lightweight** — Built with Kotlin and minimal dependencies.
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
