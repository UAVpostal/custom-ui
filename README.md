# Cogco Home

An Android home-screen launcher modelled on Samsung's One UI Home, with **Edge panels** you can theme with your own colour schemes.

Kotlin, no third-party libraries (just the Android framework), minSdk 26 (Android 8.0), targetSdk 35.

---

## Features

### Home screen (One UI Home style)
- Multiple home pages with page dots, plus a dock (4–6 icons)
- **Apps screen**: swipe up. Alphabetical, paged sideways like One UI or a vertical scrolling list, with a search bar
- **Long-press an app** → bubble with the app's shortcuts (e.g. "New message") plus App info / Remove / Add to Edge / Uninstall
- **Drag and drop**: long-press and keep moving to drag. Drop on another app to make a folder, onto a folder to add to it, near the screen edge to flip pages (a new page appears when needed), or onto Remove / Uninstall at the top
- **Folders** with editable names, One UI colour dots, and "+ Add apps"
- **Widgets**: pick from all installed widgets, then resize, reconfigure or move them
- **Edit mode**: long-press an empty spot → Wallpapers, Widgets, Edge panels, Settings, Add/Delete page
- Swipe down anywhere to open the notification shade
- Notification **badges**, as a dot or a number (needs notification access)
- Settings: Home screen layout ("Home and Apps screens" or "Home screen only"), Home and Apps grids, dock size, icon shape (One UI squircle, circle, rounded square, teardrop, system), icon size, show app names, Apps screen layout and background, add new apps to Home, lock Home screen layout, hide apps

### Edge panels
- A handle on the left or right edge that floats over every app. Swipe in (or tap) to open it. Long-press the handle and drag to move it, or lock it in place
- Panels you can swipe between (turn each one on or off and reorder them):
  - **Apps**: your favourite apps
  - **People**: favourite contacts with Call and Message buttons
  - **Tools**: flashlight, compass, tally counter, calculator, camera, alarms, Wi-Fi, volume, Bluetooth, settings (you choose which)
  - **Quick notes**: jot something down; tap a note to copy it
- Handle settings: colour, visibility, size, width, vertical position, vibrate on open
- Panel size (compact / normal / wide), background dim, and blur behind the panel (Android 12+)

### Colour schemes for the panels
- 12 built-in themes: One UI Light, One UI Dark, Frosted Glass, Midnight, Sunset, Aurora, Lavender, Neon Night, Rose Gold, Forest, Deep Ocean, Monochrome. On Android 12+ there are also two themes taken from your wallpaper colours
- **Theme editor**: background colour, optional gradient (top → bottom), opacity, accent colour, text and icon colour (or "pick automatically"), corner roundness, with a live preview
- A full colour picker: hue, saturation and brightness sliders, swatches and a hex field
- A **default theme** for every panel, plus an **override per panel**. When you swipe between panels that use different themes, the panel background fades smoothly from one to the next

---

## Building it

### Option A: Android Studio (easiest)
1. Unzip the project and choose **File ▸ Open** in Android Studio. Pick the `CogcoHome` folder.
2. Let Gradle sync. If Studio offers to upgrade the Android Gradle Plugin, you can accept.
3. Plug in your phone with USB debugging on and press **Run ▶**, or use **Build ▸ Build APK(s)**.

### Option B: GitHub (no Android Studio needed)
Push the folder to a GitHub repository. The included workflow (`.github/workflows/build-apk.yml`) builds a debug APK on every push. Download it from the run's **Artifacts** section.

### Option C: Command line
With the Android SDK installed and `ANDROID_HOME` set:
```
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## First run
1. Open **Cogco Home** from your current app drawer. That's the settings screen.
2. Tap **Set Cogco Home as default** and choose it as your Home app. To try it first without switching, use "Open the Home screen".
3. For edge panels: **Edge panels ▸ turn on**. Allow *Display over other apps* when asked, then come back.
4. Optional: turn on **App icon badges** and allow notification access.

## Customising panel colours
Edge panels ▸ **Default panel theme** picks the theme every panel uses.
To give one panel its own look: tap the panel ▸ **Colour theme**.
To make your own theme: **Create your own theme**, or long-press any preset ▸ *Duplicate and edit*.

---

## Project layout (`app/src/main/java/com/cogco/home/`)
| File | What's in it |
|---|---|
| `Core.kt` | Application class, settings (Prefs), data models, helpers |
| `Data.kt` | Installed-app list, home layout storage (JSON), icon rendering and shapes, badges |
| `HomeActivity.kt` | The home screen: pages, dock, gestures, drag and drop, edit mode, widgets |
| `AppDrawerView.kt` | Apps screen with search |
| `FolderView.kt`, `Popups.kt` | Open folders, long-press menus |
| `Views.kt` | Custom views: icon, grid, pager, page dots, tiles, panel card, theme preview |
| `Glyphs.kt` | Line icons drawn in code, so they can take any theme colour |
| `EdgePanelService.kt` | Overlay service, edge handle and panel window |
| `EdgePanels.kt` | Apps / People / Tools / Quick notes panels |
| `Schemes.kt` | Colour schemes (presets plus your own) |
| `SettingsBase.kt`, `SettingsScreens.kt` | One UI-style settings, theme gallery and editor, colour picker, pickers |
| `Widgets.kt` | Widget host |

## Notes and limits
- Swiping down for notifications uses a hidden Android API. It works on most phones, but some manufacturers block it.
- A few Samsung-only extras aren't included: app pairs, the Weather and Clipboard edges, and Good Lock-style tweaks. Android doesn't let regular apps read clipboard history.
- The edge handle runs as a small foreground service, so Android shows an "Edge panels are on" notification. You can long-press it to hide that notification category.
