# SecureBrowse

Restricted Android browser. It renders pages in a WebView and allows navigation only to administrator-approved HTTPS sites.

This repository is a complete Android Studio project. An APK is **not** produced in environments that do not have the Android SDK. Build it locally with the steps below. The expected debug APK path is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

SecureBrowse does **not** control Chrome or any other app. On a normal phone the person holding the device can leave and open another browser. That is a platform limit, not a missing feature. Device-wide confinement requires Android Enterprise device-owner provisioning and lock task mode, which this app supports only after an intentional provisioning step. The app never tries to grant itself device owner.

## What the two modes actually are

| Mode | What is enforced | What is not |
| --- | --- | --- |
| Normal browser | Allowlist inside this WebView | Other apps, Settings, uninstall, clearing storage |
| Screen pinning | This task stays in front until the user presses Back and Recents | Not kiosk lockdown. The system asks before pinning |
| Dedicated kiosk | Device owner + lock-task allowlist of this package only | Not resistant to a factory-reset from the bootloader on every device. Not a network firewall |
| Focus session | Device owner + a timer. Lock task allowlist is SecureBrowse plus the apps you selected. Optional status-bar pull-down ban (`setStatusBarDisabled`) and notification ban (lock-task notification feature off). Clock changes are blocked for the duration. When the timer ends, the shade is restored and the allowlist goes back to SecureBrowse only | Does nothing to other apps until this app is device owner. Screen pinning is not a substitute. Allowing Settings, Chrome, or the package installer gives a way out |

## Focus session

1. Provision device owner, then open Administrator.
2. Choose a length (15, 25, 45, 60, or any whole number of minutes up to 12 hours).
3. Turn **Ban status bar pull-down** on to disable the shade and quick settings. Turn **Ban notifications** on to hide lock-task notification content.
4. Allow the apps that may be opened during the session. SecureBrowse is always included so you can return to the countdown. The home screen lists those apps with Open.
5. Start the session. Ending it early requires the administrator passphrase. The running list does not change if you edit the selection; the next session uses the new list.
6. Reboot while a session is still in time restores the same allowlist and shade ban. If the clock has already passed, the session is cleared.

A normal install can store the selection and show a countdown. It cannot hide the shade or stop the person from leaving to another app.

A local administrator passphrase is **not** device-level security. It is a PBKDF2-HMAC-SHA256 verifier (210,000 iterations, random 16-byte salt). The secret is never stored and there is no default passphrase in the source. Anyone who can clear this app’s data on an unmanaged device deletes the verifier and the allowlist.

## Build and install

Requirements: Android Studio with SDK platform 36, build-tools 36, JDK 17. Android Gradle Plugin 9.4.1 and Gradle 9.6.1 are declared in the project.

1. Install [Android Studio](https://developer.android.com/studio) (Meerkat or newer can compile API 36; a current 2026 studio is fine).
2. **File → Open** and select this directory (`settings.gradle.kts`).
3. Let Gradle sync finish. If it asks to install Android SDK Platform 36, accept.
4. Choose the `app` run configuration and the **debug** build variant. Press **Run**. Studio builds, installs, and starts the debug app.
5. Debug APK without installing: **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The file is `app/build/outputs/apk/debug/app-debug.apk`.
6. Signed release APK: **Build → Generate Signed App Bundle or APK → APK → Create new keystore** (or use an existing one). Use the `release` build type. Output is under `app/build/outputs/apk/release/`. Command line equivalent, after `local.properties` contains `sdk.dir`:

   ```bash
   ./gradlew :app:assembleRelease
   ```

   A release build without a signing config produces an unsigned APK. Prefer Android Studio’s signed-APK wizard so the signing password is not written into the repo.
7. Install on a device with USB debugging:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

8. Policy unit tests do not need a device:

   ```bash
   ./gradlew :policy:test
   ```

   Instrumented tests need a device or emulator:

   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```

Minimum SDK is Android 8.0 (API 26). Target SDK is API 36. Compile SDK is API 37.

The Studio **debug** variant is marked test-only, and `dpm set-device-owner` rejects test-only packages. For kiosk provisioning use the `kiosk` variant, which is signed with the debug keystore, not debuggable, and not test-only:

```bash
./gradlew :app:assembleKiosk
adb install -r app/build/outputs/apk/kiosk/app-kiosk.apk
```

## Using the browser

1. Open SecureBrowse. The home screen states whether you are in normal mode, screen pinning, or a dedicated kiosk.
2. Tap the shield and create an administrator passphrase (10–128 characters). There is no default.
3. Add domains such as `wikipedia.org`. Turn on **Also allow subdomains** when `en.wikipedia.org` should match. The match is on a dot boundary: `notgoogle.com` does not match `google.com`.
4. Optional homepage must already be an approved `https` URL.
5. Type an address and press Go. Unapproved sites, `http`, `javascript:`, `file:`, `content:`, `intent:`, popups, and redirects are blocked in the app. Nothing is handed to an external browser.
6. JavaScript, DOM storage, and normal HTTPS loads are enabled for approved sites so single-page apps can run. File access, content access, universal file access, mixed content, and JavaScript interfaces are off. Invalid certificates are cancelled. There is no “proceed anyway”.

YouTube and Google often need extra domains (`googlevideo.com`, `ytimg.com`, `gstatic.com`, and others). Adding `youtube.com` shows those as optional suggestions. They are not added until you accept them. `googleusercontent.com` also hosts other people’s content; approve it only if you mean to.

Export and import write a JSON allowlist. The file does not contain the passphrase. Import replaces the domain list.

## Kiosk provisioning (test device)

Do this only on a device you can factory-reset. The app will not try to become device owner by itself.

Policies that **require** device owner (or another device-policy controller):

- `setLockTaskPackages` / `setLockTaskFeatures`
- `setUninstallBlocked`
- user restrictions: safe boot, factory reset, add user, physical media, unknown sources
- optional strict mode: USB debugging, USB file transfer, keyguard disabled
- starting this activity from boot without the background-activity restriction
- `clearDeviceOwnerApp` (adb-provisioned test devices only)

The allowlist, passphrase, and WebView settings do **not** require device owner.

### ADB device owner (fastest lab setup)

1. Factory-reset an emulator or a spare phone. Do not add a Google account. Device owner cannot be set when accounts exist.
2. Install the **kiosk** APK above (not the Android Studio test-only debug APK).
3. Run:

   ```bash
   adb shell dpm set-device-owner com.securebrowse.app/.kiosk.SecureBrowseDeviceAdminReceiver
   ```

4. Open SecureBrowse. In Administrator, choose **Apply device-owner policies**, then **Start lock task**. Home and Recents no longer leave the app. `LOCK_TASK_FEATURE_NONE` hides the system navigation features lock task is allowed to hide.
5. Reboot. If auto-start is on, the device-owner app opens again.

### QR / setup-wizard provisioning

On the welcome screen of a factory-reset device, tap six times and scan a provisioning QR whose admin component is:

```text
com.securebrowse.app/com.securebrowse.app.kiosk.SecureBrowseDeviceAdminReceiver
```

The QR must also include a download URL and signature checksum for the APK. Those values come from your distribution site; this repo does not host an APK. `GetProvisioningModeActivity` returns `PROVISIONING_MODE_FULLY_MANAGED_DEVICE`. `PolicyComplianceActivity` applies lock-task policy and returns `RESULT_OK`.

### Leaving kiosk

1. Open **Administrator** (shield).
2. Enter the passphrase. The unlock lasts five minutes and is kept only in memory.
3. Tap **Stop lock task**. This calls `stopLockTask()` and turns off auto-start so the next resume does not immediately lock again.
4. To drop device owner on an **adb test** device, use **Remove device-owner role on ADB test devices**. That calls `clearDeviceOwnerApp`. It fails on many production-provisioned devices; factory-reset those.
5. There is no hidden gesture that bypasses the passphrase. Back at the root of a dedicated lock task does nothing.

Strict mode blocks USB debugging. Turn it on only after you can get back in. Factory reset is also restricted while device-owner policies are applied; recovery-mode reset may still be possible and depends on the OEM.

## Allowlist behavior

Approved:

- `https://google.com` and, if subdomains are enabled, `https://www.google.com`
- any port on that host
- redirects and popup targets whose final host is approved (popups stay in this WebView)
- JavaScript navigations to an approved host, with or without a user gesture

Blocked:

- `https://notgoogle.com`, `https://google.com.evil.com`, punycode lookalikes
- `http://` even for an approved host (cleartext is also disabled in network security config)
- `javascript:`, `file:`, `content:`, `intent:`, `data:` documents, `market:`, `mailto:`
- URLs with embedded usernames or passwords
- subresources from hosts that are not approved, unless an administrator turns that block off
- downloads unless downloads are enabled **and** the URL is approved HTTPS
- any TLS error (`handler.cancel()` only; `proceed()` is never called)

`about:blank` is the empty internal page. Service-worker fetches go through the same host check. WebSockets are a known WebView gap: `shouldInterceptRequest` does not see them on current Android releases, so an approved page can still open a `wss:` socket. Treat that as a browser-engine limit. A managed always-on VPN or private DNS filter is the layer that covers other apps and sockets; this app does not install a VPN.

User-installed certificate authorities are not trusted. SSL inspection CAs added as user credentials will fail closed on purpose.

## Tests

JVM tests in `:policy` cover approved domains, subdomains, lookalikes, redirects, `javascript:` navigation, popups, schemes, HTTP versus HTTPS, SSL cancel, repository restart, passphrase lockout, and kiosk posture (including “screen pinning is not a kiosk” and “exit requires authentication”).

`app/src/androidTest` checks that provisioning constants match `DevicePolicyManager`, that a normal install is not device owner, and that opening `https://example.com` shows the blocked screen instead of crashing.

## Security limits (read this before deploying)

- The allowlist is inside this process. It does not apply to Chrome, Custom Tabs, or any other package.
- Lock task stops ordinary users opening those packages only after this app is device owner and is the only lock-task package.
- Approved sites can still be malicious. The allowlist is not a content filter.
- Third-party scripts, iframes, and downloads are blocked only when their hosts are not approved and the third-party switch is on. Turning that switch off lets any HTTPS host supply scripts to an approved page.
- A determined person with the unlocked device, USB debugging, or physical recovery access can leave an unmanaged install. Do not describe that deployment as tamper-proof.
- Clearing app storage resets the passphrase. Lock task is what keeps Settings out of reach, and it works only in the dedicated-device configuration above.
- No passwords, cookies, or full URLs are written to logcat. Query strings are omitted from the blocked-page explanation because they can carry tokens. WebView’s own cookie store is still used so approved sites can sign in. Clear it from Administrator.
- Backups are disabled. Policy and the verifier are encrypted with an Android Keystore AES-GCM key and kept in `noBackupFilesDir`. Losing the keystore key makes the files unreadable; the admin screen can reset the allowlist after the passphrase is accepted. There is no passphrase backdoor.
