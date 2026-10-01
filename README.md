# SnipNotes

Select text in any app → it's saved to your notes automatically. No copy, no paste.

## How it works
- **Background capture (main feature):** an Accessibility service runs in the background and receives
  Android's "text selection changed" events. When a selection stays still for ~1.2 s (you finished
  dragging the handles) the text is appended to your notes. Re-adjusting the selection within 20 s
  replaces the snippet instead of duplicating it. Password fields are always ignored.
- **One-tap fallback:** some apps (a few PDF readers, games, custom-drawn text) don't report selections.
  For those, "Save to notes" appears in the selection menu (⋮ → Save to notes) and the Share sheet.
- **Where notes go:** the app's own document (editable on the main screen). Optionally tap
  **Link a file** to mirror everything into a `.md`/`.txt` file in Documents, Downloads, or Drive.
- **Quick Settings tile** "SnipNotes capture" pauses/resumes capture.

## Build the APK (pick one)

### A. No install needed — GitHub builds it
1. Create a new GitHub repo and upload the contents of this folder (keep `.github/`).
2. Open the repo's **Actions** tab → "Build APK" runs automatically (≈3 min).
3. Open the finished run → download **SnipNotes-apk** → unzip → `app-release.apk`.

### B. Android Studio
Open this folder in Android Studio → wait for Gradle sync → **Build → Build APK(s)**,
or plug in the phone with USB debugging and press **Run**.

## Install & set up on the phone (one time)
1. Copy the APK to the phone and open it (allow "Install unknown apps" for your file manager).
2. Open SnipNotes → **Turn on background capture** → Accessibility → Installed/Downloaded apps →
   **SnipNotes auto-capture → On**.
   - Switch greyed out (Android 13+)? Settings → Apps → SnipNotes → ⋮ → **Allow restricted settings**, then retry.
3. Tap **Keep alive** → Allow (stops the battery optimiser from killing it).
   - Xiaomi/Redmi/POCO: also enable **Autostart** for SnipNotes. Samsung: Battery → Unrestricted.
   - OnePlus/Oppo/Realme/Vivo: App info → Battery → Allow background activity.

## Notes
- iPhone isn't supported: iOS doesn't let any app read selections in other apps.
- Settings on the main screen: auto-save on/off, add app name & time, "Saved" popup,
  ignore text boxes you're typing in.
