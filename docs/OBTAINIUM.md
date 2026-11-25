# Installing and updating H9 OPS with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight
from their GitHub releases and tells you when a new version is out. No app
store and no account are needed, and H9 OPS updates arrive the moment they're
released.

## 1. Get Obtainium

Install Obtainium from [F-Droid](https://f-droid.org/packages/dev.imranr.obtainium.fdroid/)
or from its [GitHub releases](https://github.com/ImranR98/Obtainium/releases)
(download the APK on your phone and open it; Android asks you to allow
installs from your browser or file manager once).

## 2. Add H9 OPS

1. Open Obtainium and go to **Add App**.
2. In **App Source URL** paste:

   ```
   https://github.com/Deadly447/H9-OPS
   ```

3. Tap **Add**. Obtainium finds the latest release and its APK.
4. Tap **Install**, and allow Obtainium to install apps when Android asks.

## 3. Updates

Obtainium checks for new releases in the background and shows a notification
when there's one. Open it and tap **Update** (or turn on background updates in
Obtainium's settings).

Every H9 OPS release is signed with the same key, so updates install over the
old app and keep your messages, settings, map and history.

## Check it's the real app (optional)

The release APKs are signed with this certificate (SHA-256):

```
16:B5:99:71:23:96:78:35:2A:39:C3:6E:D7:14:F8:22:2B:52:EA:F8:88:77:03:CE:7F:BC:B2:FA:74:74:B4:39
```

[AppVerifier](https://github.com/soupslurpr/AppVerifier) can compare an
installed app against it: package `io.github.deadly447.h9ops` with this
fingerprint. If it doesn't match, don't use that APK.

## Without Obtainium

Download `H9-OPS-x.y.z.apk` from the
[Releases page](https://github.com/Deadly447/H9-OPS/releases) on your phone
and open it. Check the page again for updates.
