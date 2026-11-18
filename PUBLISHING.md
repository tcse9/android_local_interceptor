# Publishing to Maven Central

The repository is https://github.com/tcse9/android_local_interceptor.
The Maven coordinates are `io.github.tcse9:inspector:0.2.0`.
The source is distributed under the MIT License.

## 1. Verify your namespace

Sign in to https://central.sonatype.com using your tcse9 GitHub account.
Open Namespaces and confirm that `io.github.tcse9` is verified.
If it is missing, add it and follow the portal's ownership verification instructions.

Reference: https://central.sonatype.org/register/namespace/

## 2. Prepare your machine

Install JDK 17, Android SDK Platform 35, Android SDK Platform-Tools, Python 3.9+, and GnuPG.
On macOS, GnuPG can be installed with `brew install gnupg`.

Clone the repository and configure the SDK and Java environment.

```sh
git clone https://github.com/tcse9/android_local_interceptor.git
cd android_local_interceptor
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

Use the corresponding SDK path and JDK 17 configuration on other operating systems.
Run the preparation script in Bash, for example through WSL on Windows.

## 3. Create or choose your signing key

Reuse an existing PGP signing key, or generate one.

```sh
gpg --full-generate-key
gpg --list-secret-keys --keyid-format long --fingerprint
```

Copy the full fingerprint for the key you want to use. Publish its public key.

```sh
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_FULL_FINGERPRINT
```

Keep the private key and passphrase in your local GPG keyring.

Reference: https://central.sonatype.org/publish/requirements/gpg/

## 4. Verify the release

```sh
./gradlew :demo:assembleDebug :demo:assembleRelease :inspector:lintDebug
```

## 5. Generate the signed bundle

Replace the fingerprint argument with your actual full fingerprint.

```sh
./scripts/prepare-central.sh 0.2.0 YOUR_FULL_FINGERPRINT
```

Enter the GPG passphrase when prompted.
The script builds the release AAR, sources, Javadoc and Maven metadata, signs the publication,
verifies the signatures and checksums, and creates this ZIP.

```text
inspector/build/central-bundle-0.2.0.zip
```

The script prepares a local archive. The portal upload is the next step.

## 6. Upload and publish

Open https://central.sonatype.com and choose Publish Component.
Use `io.github.tcse9:inspector:0.2.0` as the deployment name.
Upload `inspector/build/central-bundle-0.2.0.zip`.
Wait for validation, review its results, and click Publish.
Wait until the deployment reaches Published.

Reference: https://central.sonatype.org/publish/publish-portal-upload/

## 7. Consume the published library

Add `mavenCentral()` to the consuming project's dependency repositories, then add this
dependency to its Android app module.

```kotlin
dependencies {
    debugImplementation("io.github.tcse9:inspector:0.2.0")
}
```

Follow README.md to initialize the interceptor and start the local dashboard.
The desktop dashboard is included in this GitHub repository.

## 8. Publish subsequent releases

Published Maven Central versions are immutable. Choose a new version for each release.
For example, prepare version 0.2.1 with this command and upload the resulting ZIP.

```sh
./scripts/prepare-central.sh 0.2.1 YOUR_FULL_FINGERPRINT
```

Update the default version in `inspector/build.gradle` and README dependency examples
when you adopt a new release as the default.

Reference: https://central.sonatype.org/faq/can-i-change-a-component/
