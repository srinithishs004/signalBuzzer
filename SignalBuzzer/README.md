# Signal Buzzer

A minimal Android app: press Volume Up/Down on one phone, the other phone
(same Room ID) buzzes — even with its screen off. Tap STOP (in the
notification or in the app) to stop the buzzing.

I could not produce a ready-to-install .apk directly because building it
requires downloading Firebase/Gradle dependencies from the internet, which
the environment I built this in doesn't have access to. Below is everything
needed to build the real .apk yourself, in about 10 minutes.

## How it works
- Both phones run the exact same app.
- On first launch, you type a shared "Room ID" (any text, e.g. `family123`).
- Pressing Volume Up/Down (while the app is open) writes a tiny "ping" to
  Firebase under `rooms/<roomId>/ping`. The volume itself is NOT changed —
  the button press is fully captured by the app instead.
- A background service on every phone with that app open is listening to
  that same path. When a ping arrives from another device, it vibrates the
  phone in a repeating pattern and shows a notification with a STOP button.
  This works with the screen off / app in the background because it runs
  as an Android foreground service.
- Tapping "Stop Vibration" in the app, or STOP in the notification, stops it.

One real OS limitation worth knowing: Android does not allow any app to
capture hardware volume-key presses while it isn't the active foreground
app. So *sending* a signal requires that phone's screen on with the app
open. *Receiving/vibrating* has no such limitation and works in the
background or with the screen off.

## 1. Create a free Firebase project
1. Go to https://console.firebase.google.com and create a new project (no
   billing required for this use case).
2. In the project, click "Add app" → Android. Use package name
   `com.example.signalbuzzer` (matches this project exactly).
3. Download the generated `google-services.json` file.
4. Place that file at `SignalBuzzer/app/google-services.json` (same folder
   as `app/build.gradle`).
5. In the Firebase console, go to Build → Realtime Database → Create
   Database (choose any region) → start in test mode.
6. Optional but recommended — replace the rules with:
   ```json
   {
     "rules": {
       "rooms": {
         "$room": {
           ".read": true,
           ".write": true
         }
       }
     }
   }
   ```
   This keeps things open under `rooms/` only (fine for a personal pairing
   app like this) rather than your whole database being world-writable.

## 2. Build the app
1. Install Android Studio (free): https://developer.android.com/studio
2. Open this `SignalBuzzer` folder as a project (File → Open).
3. Let Gradle sync — this needs internet once, to download the Firebase/
   Android libraries.
4. Plug in a phone (USB debugging enabled) and click Run, OR go to
   Build → Generate Signed Bundle / APK → APK, to get an installable
   `.apk` file you can transfer/share to any phone.
5. Repeat the same build (same `google-services.json`) for the second
   phone — or just send the same .apk to both phones.

## 3. Use it
1. Open the app on both phones. On first launch, enter the same Room ID
   on both (e.g. `family123`).
2. Leave the app open (or just running in background) on the receiving
   phone.
3. On the sending phone, keep the app open and press Volume Up or Down —
   the other phone buzzes.
4. Stop the buzz from the notification's STOP action, or the in-app
   "Stop Vibration" button.

There's also a "Send Test Signal" button in the app for testing without
needing to press a hardware volume key (handy on an emulator).
