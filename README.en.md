<p align="center">
  <img src="icon.png" alt="Nom Keyboard" width="200" />
</p>

# Nom Keyboard for Android

> 👉 _This document is in **English**. **[Phiên bản tiếng Việt](README.md)**._

A modernised Chữ Nôm input method for Android. The UI has been redesigned to look and feel like **Gboard**.

## 🌟 Highlights

- **Gboard-style QWERTY**: rounded keys, grey function keys, centred indicator bar on the space key, two symbol pages (`?123` and `=\<`).
- **Telex Vietnamese input**:
  - Vowel modifiers: `aa → â`, `aw → ă`, `ee → ê`, `oo → ô`, `ow → ơ`, `uw → ư`, `dd → đ`
  - Tone marks: `s` (sắc), `f` (huyền), `r` (hỏi), `x` (ngã), `j` (nặng), `z` (clear tone)
- **Live Nom character suggestions**: single-syllable dictionary of **6,686** syllables with **25,059** candidates, plus a compound-word dictionary of **1,636** entries (flattened into **2,648** lookup keys). Tap any candidate to commit.
- **Diacritic-tolerant lookup**: both fully-toned and plain-ASCII queries resolve to the right candidates. For example `chunom`, `chữ nôm`, `chữnôm` all yield **𡦂喃 / 𡨸喃**; `vietnam` → **越南**; `hànội` → **河內**.
- **Lightweight frequency learning**: recently used Nom characters bubble up in the suggestion bar (persisted in SharedPreferences).
- **User dictionary**: add, edit or delete your own entries from **Settings ▸ User dictionary**. User entries always take priority over the built-in dictionary, so you can override or extend the defaults. **Import / export** in the same tab-separated `key<TAB>candidate1 candidate2 …` format used by the bundled dictionaries — pick any file via the system file picker, and choose **merge** or **replace** on import.
- **Light / dark / follow-system** themes.
- Optional **haptic feedback** and **key sound**.
- Bundled **Han-Nom Gothic** font (~12 MB) for correct glyph rendering of Nom characters in the candidate bar.

## 🚀 Building (Android Studio)

1. Install Android Studio Giraffe (2022.3) or newer.
2. Choose **Open** and point it at `nom_keyboard/`.
3. When Android Studio reports a missing `gradle-wrapper.jar`, click **Sync / Use Gradle wrapper** and it will download the wrapper automatically.
4. Wait for Gradle to fetch the toolchain: Android Gradle Plugin 8.5.2, Kotlin 1.9.25, Gradle 8.9.
5. Run **Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)** to produce an `.apk`.
6. Install the APK on your phone, then:
   - Open **Settings ▸ Language & input ▸ Manage keyboards** and enable **Nom Keyboard**.
   - In any text field, use the keyboard picker to switch to **Nom Keyboard**.

## 📜 Requirements

- Android 7.0 (API 24) or newer.
- About 20 MB of installation space, mainly because of the bundled font.
