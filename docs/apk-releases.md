# SonicMusic APK releases

The updater uses the existing GitHub release endpoint and Android Download Manager. A release contains one standalone APK and its Gradle-generated companion file, `sonicmusic-update.json`. Upload both through the APK release workflow. Older builds still discover the update by its numeric release version.

## One-time signing setup

1. Obtain the original release keystore and a copy of the last APK users actually installed. Run Android SDK Build-Tools' `apksigner verify --print-certs previous.apk` and independently confirm that the signer fingerprint belongs to the original production key.
2. In the GitHub repository, set the Actions variable `RELEASE_CERT_SHA256` to the signer's SHA-256 fingerprint. Add the Actions secrets `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. The keystore secret is the original keystore bytes encoded as Base64. Configure the existing `INNERTUBE_API_KEY` and `YOUTUBE_API_KEY` release secrets. Keep the private key and passwords out of Git and release assets.
3. Ensure the last published GitHub release contains the historical APK. When it has multiple APKs, the canonical universal asset must be named `app-release.apk`. Protect the default branch and restrict publishing permissions to maintainers and the release workflow.

Launch **SonicMusic APK release** from the repository default branch and enter a higher numeric version name such as `2.0.1`. Gradle automatically derives the version code from the last published APK. The pipeline verifies the original certificate, package name, version, minimum SDK and native architectures. It installs over the downloaded previous release on API 26 and 35, then checks launch, the installed version, app data retention, and a seeded playlist in the existing Room database. Publication waits for both upgrade runs and verifies the uploaded bytes. A failed draft can be retried from the same commit.

A local release uses the same Gradle gate. Set the variables described in `local.properties.example`, including `PREVIOUS_RELEASE_APK`, then run `./gradlew :app:assembleRelease` (`.\gradlew.bat` on Windows). The signed APK and manifest are generated under `app/build/outputs/apk/release`. The GitHub workflow also validates the in-place upgrade before publishing.

The release APK includes ARM64, ARMv7, x86, and x86-64 native libraries. The verifier checks 16 KB compatibility for 64-bit native libraries. Gradle compresses those libraries for extraction at install time with the current Android Gradle Plugin. [Android 16 KB page size guidance](https://developer.android.com/guide/practices/page-sizes)

## Failure and recovery

- Verification failures or a cancelled or rejected Android install leave the currently installed version and its data in place. SonicMusic reports the failure through the existing update status, with an optional system notification.
- If an installed release has a functional regression, restore the known-good implementation while retaining compatibility with every already-shipped database schema. Release that recovery as a higher-version update using the same keystore. On ordinary Android devices a lower-version APK cannot be used as a data-preserving rollback.
- A build signed with a different certificate cannot update an existing installation in place. Recover the signing key that matches the installed distribution. The pipeline deliberately stops if the historical asset, original key, or fingerprint cannot be verified.
