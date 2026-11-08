# Mive LED Pulse - Persistent Notification LED
### Specially engineered for the Mive Style Folder 1 (ALT AT-M120 / Android 12 Go)

## The Problem
On the Mive Style Folder 1 flip phone, incoming notifications (KakaoTalk, SMS, WhatsApp, Missed Calls) trigger only **1 single blink** on the front exterior chassis LED and then completely power off. If you don't look at the phone in that split second, you miss the notification entirely!

## The Solution
**Mive LED Pulse** runs an ultra-low-power background foreground listener. As long as any unread notification from your selected apps is pending:
1. It pulses the physical exterior notification LED every **2 seconds**.
2. It uses silent notification light channels so it never makes annoying noises or vibrates your desk every 2 seconds.
3. As soon as you open the flip phone or dismiss the notification, the LED **immediately turns off**.
4. Includes Quiet Hours / Sleep Mode (23:00 - 07:00).

---

## 3 Ways to Get the .APK

### Option 1: 100% Free Cloud Build (Zero Android Studio Needed!)
1. Create a free account on [GitHub.com](https://github.com).
2. Create a new repository and upload this extracted folder (or push via Git).
3. Click the **Actions** tab on GitHub.
4. The pre-configured **Build Mive LED Pulse APK** workflow will automatically compile `app-debug.apk`.
5. Download the APK directly onto your phone and install!

### Option 2: Android Studio (Developers)
1. Open Android Studio.
2. Select **Open** and choose this extracted folder.
3. Go to **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Your `.apk` will be saved to `app/build/outputs/apk/debug/app-debug.apk`.

### Option 3: ADB Command line
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Crucial Mive Style Folder (AT-M120) Settings
Because Mive Style Folder runs **Android 12 Go Edition**, aggressive power managers will stop background services unless you grant these 2 settings:

1. **Notification Access**:
   Open Mive LED Pulse > tap **Grant Notification Access** > toggle **Mive LED Pulse** to **ON**.
2. **Disable Battery Optimization (Doze bypass)**:
   In Mive LED Pulse > tap **Disable Battery Sleep Killer** > choose **Don't optimize / Unrestricted**.
3. *(Optional)* **DuraSpeed**:
   If your Mive model has DuraSpeed in Settings > Battery, toggle it off or whitelist Mive LED Pulse.
