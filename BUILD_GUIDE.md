# Bill Reminder Android App — Build & Setup Guide

## What This App Does

- **Gmail Integration**: Signs in via Google OAuth 2.0 and reads your Gmail to detect bill/payment emails automatically
- **Smart Bill Detection**: Uses keyword + regex parsing to find due dates, amounts, and categories
- **Google Calendar**: Adds bill due dates directly to your Google Calendar with color-coded events and reminders
- **Local Reminders**: Sets Android alarm notifications (1 day before due date, at 9 AM)
- **Manual Entry**: Add bills manually with date picker
- **Status Tracking**: Tracks Pending / Due Soon / Overdue / Paid status with color coding
- **Swipe to Sync**: Pull-to-refresh triggers Gmail re-scan

---

## STEP 1: Set Up Google Cloud OAuth (Required — 10 minutes)

This is essential. Without it, the app cannot access Gmail or Calendar.

### 1.1 Create a Project
1. Go to [console.cloud.google.com](https://console.cloud.google.com)
2. Click **New Project** → name it "BillReminder" → Create

### 1.2 Enable APIs
1. Go to **APIs & Services → Library**
2. Search and enable **Gmail API**
3. Search and enable **Google Calendar API**

### 1.3 Configure OAuth Consent Screen
1. Go to **APIs & Services → OAuth consent screen**
2. Select **External** → Create
3. Fill in:
   - App name: Bill Reminder
   - User support email: your email
   - Developer contact: your email
4. Click **Save and Continue**
5. On **Scopes** page, click **Add or Remove Scopes**, add:
   - `.../auth/gmail.readonly`
   - `.../auth/calendar`
6. Save and Continue
7. On **Test Users** page, add your Gmail address (while in testing mode)
8. Save

### 1.4 Create OAuth Credentials
1. Go to **APIs & Services → Credentials**
2. Click **+ Create Credentials → OAuth Client ID**
3. Application type: **Android**
4. Package name: `com.billreminder.app`
5. For SHA-1 fingerprint, run this command in your terminal:
   ```bash
   # On Mac/Linux:
   keytool -keystore ~/.android/debug.keystore -list -v -storepass android | grep SHA1
   
   # On Windows:
   keytool -keystore %USERPROFILE%\.android\debug.keystore -list -v -storepass android | findstr SHA1
   ```
6. Paste the SHA-1 into the field → Create
7. Download the `google-services.json` — you'll need it in Step 3

---

## STEP 2: Install Android Studio

1. Download from [developer.android.com/studio](https://developer.android.com/studio)
2. Install and open Android Studio
3. During setup, install the Android SDK (API level 34 recommended)

---

## STEP 3: Import the Project

1. Open Android Studio
2. Click **File → Open** (or "Open" from the welcome screen)
3. Navigate to the `BillReminder` folder → click OK
4. Wait for Gradle sync to complete (downloads dependencies, ~5 minutes first time)

### Place the google-services.json
Copy the `google-services.json` you downloaded into:
```
BillReminder/app/google-services.json
```
**This file is required.** Add the Google Services plugin to your `app/build.gradle`:
```gradle
// Add at top of plugins block:
plugins {
    id 'com.google.gms.google-services'
    ...
}
```
And to root `build.gradle` classpath:
```gradle
classpath 'com.google.gms:google-services:4.4.1'
```

---

## STEP 4: Build the Debug APK

### Option A: In Android Studio (Easiest)
1. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Wait for build (1-3 minutes)
3. Click **locate** in the notification, or find APK at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Option B: Command Line
```bash
# In the BillReminder directory:
./gradlew assembleDebug

# APK will be at:
app/build/outputs/apk/debug/app-debug.apk
```

---

## STEP 5: Install on Your Phone

### Enable Developer Options & USB Debugging
1. On your Android phone: Settings → About Phone
2. Tap **Build Number** 7 times
3. Go back to Settings → Developer Options → Enable **USB Debugging**

### Install via ADB
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Or: Transfer & Install Directly
1. Copy the APK to your phone via USB or cloud storage
2. On your phone: Settings → Security → Enable **Install Unknown Apps** for your file manager
3. Open the APK file and tap Install

---

## STEP 6: First Run

1. Open **Bill Reminder** on your phone
2. Tap **Sign in with Google**
3. Select your Gmail account
4. Grant permissions for Gmail (read) and Calendar
5. The app will automatically scan your Gmail for bill emails
6. Found bills appear in the list — tap any to view details
7. Tap **📅 Add to Google Calendar** to create a calendar event
8. Tap **🔔 Remind Me** for a push notification reminder
9. Pull down to re-sync Gmail at any time

---

## App Architecture

```
BillReminder/
├── app/src/main/java/com/billreminder/app/
│   ├── auth/          AuthManager.kt          — Google Sign-In
│   ├── data/          BillDatabase.kt, DAO     — Room local DB
│   ├── model/         Bill.kt                  — Data model
│   ├── repository/    GmailRepository.kt       — Gmail API
│   │                  CalendarRepository.kt    — Calendar API  
│   │                  BillRepository.kt        — Unified repo
│   ├── ui/            MainActivity.kt          — Bill list
│   │                  LoginActivity.kt         — Sign-in screen
│   │                  BillDetailActivity.kt    — Bill detail
│   │                  AddBillActivity.kt       — Manual entry
│   │                  BillAdapter.kt           — RecyclerView
│   ├── util/          EmailParser.kt           — Bill detection
│   │                  ReminderScheduler.kt     — Alarms
│   ├── receiver/      AlarmReceiver.kt         — Notification trigger
│   └── viewmodel/     MainViewModel.kt         — UI state
└── app/src/main/res/  layouts, drawables, etc.
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Sign-in fails with error 10 | SHA-1 fingerprint mismatch. Re-check Step 1.4 |
| Sign-in fails with error 12501 | User cancelled — try again |
| "No bills found" after sync | Your bills may use unusual keywords. Try adding manually |
| Calendar permission denied | Re-check OAuth scopes include calendar |
| Notifications not showing | Grant notification permission in Settings → Apps → Bill Reminder |
| "Exact alarm" dialog appears | Tap Settings and allow exact alarms |

---

## Publishing to Play Store (When Ready)

1. Build a **signed release APK**:
   - Build → Generate Signed Bundle / APK → APK → Create new keystore
   - Keep the keystore file safe — you'll need it for every update
2. Create a Play Console account at [play.google.com/console](https://play.google.com/console) ($25 one-time fee)
3. Create new app → upload APK → fill in store listing
4. Submit for review (typically 1-3 days)

---

## Privacy Notes

- The app requests **read-only** Gmail access — it cannot send emails
- Email content is processed locally on your device and not sent anywhere
- Bills are stored in a local SQLite database on your device
- Calendar events are added to your own Google Calendar

