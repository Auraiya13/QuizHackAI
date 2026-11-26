# 🧠 Quiz Hack AI

AI-Powered Live Quiz Answer Helper — automatically detects quiz questions from any app and finds answers using Gemini AI.

## ✨ Features

- 🤖 **Gemini AI** — Accurate answers powered by Google's AI
- 📱 **Auto Detection** — Accessibility Service reads questions automatically  
- 📸 **Image Support** — Handles image-based quiz questions
- 🎤 **Voice Commands** — Ask questions in Hindi/English by voice
- 💬 **Floating Overlay** — Draggable popup shows answers over any app
- 🔍 **Multi Search** — Gemini AI → Wikipedia → Google fallback chain
- 🌙 **Dark Theme** — Modern dark UI

## 🚀 Quick Start

1. Download APK from [Releases](../../releases) or build from source
2. Install and open the app
3. Enable **Accessibility Service** (Settings → Accessibility → Quiz Hack AI)
4. Paste your **Gemini API Key** (free from [aistudio.google.com](https://aistudio.google.com))
5. Tap **Start Overlay**
6. Open any quiz app — answers appear automatically! ✅

## 🔑 Gemini API Key (Free)

1. Go to [aistudio.google.com](https://aistudio.google.com)
2. Click "Get API Key" → "Create API Key"
3. Paste in the app

> The app works without an API key too (Wikipedia + Google fallback), but AI answers are more accurate.

## 🛠️ Build

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## 📄 License

MIT License — Free to use and modify.

## 🙏 Credits

Inspired by [SubhamTyagi/loco-answers](https://github.com/SubhamTyagi/loco-answers)
