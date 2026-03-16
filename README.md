# **CloudPlayPlus**

A lightweight, fast, and minimal Android launcher for **Xbox Cloud Gaming**.  
CloudPlayPlus focuses on simplicity, performance, and a clean experience—ideal for handhelds like the **Logitech G Cloud**, Android tablets, and phones.

---

## 🚀 Features

- **Instant Launch** — Opens Xbox Cloud Gaming quickly without extra UI clutter.
- **Native Sharpening Solution** CloudPlayPlus applies a lightweight sharpening pass to make Xbox Cloud Gaming streams clearer, reducing the soft, blurred look of video‑based streaming and improving text and UI crispness without adding latency and maintaining strong battery life.  
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

### **Option 2: Install APK (Coming Soon)**
Releases will be published under the **Releases** tab once the project stabilizes.

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