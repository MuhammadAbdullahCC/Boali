# Google Maps in Boali

Boali v1.0 supports the official **Maps SDK for Android** in Lyn's route tab.
It draws your **actual recorded GPS points** and does not replace the background GPS recording service.

The APK compiles without a key, but Google Maps will not be enabled until a valid key is supplied when building.

## Set up the key

1. Open https://console.cloud.google.com/ and create/select a Google Cloud project.
2. Enable billing for the project (required by Google Maps Platform).
3. Enable **Maps SDK for Android**, NOT the JavaScript API or Maps Embed API.
4. In Google Maps Platform > Keys & Credentials, create an API key.
5. API-restrict it to **Maps SDK for Android**.
6. Application-restrict it to Android application **com.lynboa.walk** and the SHA-1 of the Boali APK signing certificate. The CI logs include the SHA1 fingerprint under "Confirm cached signing key is readable." Use the SHA1, not SHA256.
7. In GitHub, open repository **Settings > Secrets and variables > Actions > New repository secret**. Create secret **GOOGLE_MAPS_ANDROID_API_KEY** with the API key as its value.
8. Open **Actions > Build installable Android APK > Run workflow**.
9. When it succeeds, download the *actual APK*, not a ZIP, from:
   https://github.com/MuhammadAbdullahCC/Boali/releases/download/boali-android-latest/Boali-Latest-Android.apk

The new APK uses Google Maps as the default when the key is configured. Otherwise the existing native street map remains selected. You can switch back at any time.

**Cost/security:** Google requires a billing-enabled project, even if usage falls within its free allowances. Set a billing budget and quota. Never publish an unrestricted key in the GitHub repository.

**Offline maps:** Google's SDK does not support manually imported MBTiles and does not permit arbitrary prefetching of Google map tiles for offline storage. Existing offline MBTiles remain available with Boali's native OSM-based map. GPS recording and GPX history work without street tiles.
