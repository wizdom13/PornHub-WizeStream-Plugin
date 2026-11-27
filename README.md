# PornHub for WizeStream

A Kotlin service plugin implementing **native WizeStream plugin API 2.0**. It adds **PornHub**
to WizeStream's service selector and uses the host's networking, search results, local playlists
and player.

- **Plugin:** `org.wizestream.plugin.pornhub`, version **1.0.3** (`versionCode: 4`).
- **Host:** WizeStream **1.52.2 or newer** (`versionCode: 1052002`), on Android **7/API 24 or newer**.
- **Contract:** `host: "wizestream"`, `apiVersion: "2.0"`.
- **Access:** public, anonymous responses; no API key or account credentials are required.
- **Audience:** adults only. The generated repository entry has **`nsfw: true`**, and extracted
  streams report an age limit of **18**.

[WizeStream](https://github.com/wizdom13/WizeStream) ·
[Plugin API and installation guide](https://github.com/wizdom13/WizeStream/blob/pipe/docs/plugins.md) ·
[Report a plugin issue](https://github.com/wizdom13/PornHub-WizeStream-Plugin/issues)

## Supported features

| Feature | Behavior |
| --- | --- |
| Video links | Open watch, `video/show` and embed links; normalize recognized desktop, mobile and language hosts on the `.com` and `.org` domains to stable video IDs. |
| Search | Search public videos with encoded queries, metadata cards and validated pagination. |
| Discovery | Browse the public **Videos** listing with pagination. |
| Channels | Open linked model, performer, user and channel profiles from video details and video cards, then browse their public **Videos** tab with pagination. |
| Playback | Expose advertised progressive MP4, HLS and DASH sources. Resolve `get_media` JSON responses before offering their media URLs to the player. |
| Player headers | Supply Referer and Origin headers for media sources and manifests. |
| Metadata | Read titles, thumbnails, uploader names, duration, available counters and structured upload dates. |
| Captions | Expose the player's `closedCaptionsFile` SRT or VTT track when supplied. |
| Saved items | Preserve the host-assigned service identity for history and local playlists. |
| Downloads | Expose progressive MP4 sources when the provider supplies them; no MP4 URL is invented for HLS-only content. |

Cards are scoped to the main results container. Duplicate videos and recognized advertisements
are discarded, and unknown metadata remains unknown. Signed media query parameters are preserved;
media sources are marked non-cacheable, and extracted information has a five-minute cache lifetime.

Search pagination accepts equivalent provider-host redirects and capitalization changes while
preserving the search terms, filters and requested page. An invalid Next link stops pagination
and reports its error without discarding valid results already fetched.

Each HTML or media-metadata request uses the host's asynchronous downloader with a **30-second
overall deadline**, including queueing and response download. A stalled request is cancelled
and reported as a timeout; leaving an interrupted extraction cancels its request. Final redirect
URLs, request headers and provider error categories are preserved. There are no automatic retries
or alternate-network fallbacks. The deadline applies to each request, not to a whole multi-request
extraction or to media playback.

### Current scope

Subscription import, remote playlists, comments, related videos, search suggestions and live
broadcasts are not implemented. Uploader navigation uses published profile links; a name without
a supported link remains non-clickable. Account login, premium content and private libraries are
outside this implementation.

The parser reads literal player JSON and structured HTML. It does not execute website JavaScript,
synthesize consent or login cookies, or bypass age verification, geographic restrictions, payment,
DRM or browser challenges. Removed, restricted and unavailable resources produce extraction errors.
The provider can change its markup or reject requests from particular networks or regions.
The request deadline prevents an indefinitely pending network request; it does not establish why
a particular device was stuck loading or guarantee that the provider returns usable results.

## Install

Installation requires a published **signed `.ppp` package** and its matching **`index.json`**.
A source checkout or the GitHub repository page itself is not an installable plugin repository.

Once the signed assets are publicly accessible:

1. Obtain the publisher's direct HTTPS `index.json` URL from a published release or the
   [WizeStream Plugins catalog](https://github.com/wizdom13/WizeStream-Plugins).
2. In WizeStream, open **Settings → Plugins**, enable plugins, and add that repository URL
   if it is not already configured.
3. Check the publisher and install **PornHub**. Its repository entry is marked as NSFW.
4. Restart WizeStream when prompted, then select **PornHub** as the active service.

After the publisher configures `SIGNING_KEY`, the [release workflow](.github/workflows/release.yml)
publishes signed packages and an index in the rolling **`repo`** GitHub release. The stable
installation URL is:

```text
https://github.com/wizdom13/PornHub-WizeStream-Plugin/releases/download/repo/index.json
```

WizeStream's **Install from project or URL** also accepts this public GitHub project URL. For
**Install from file**, select the versioned `.ppp` and a freshly downloaded matching `index.json`
from the same publisher release. A saved catalog from an older version does not match a newer
package; keeping it under a distinct filename helps avoid choosing an older download. Version
1.0.3 requires its own signed release and is not implied to be published by this source update.

Ordinary CI artifacts use disposable keys and are **test-only**. Distribution uses the permanent
publisher key through the release workflow. Inclusion in the shared WizeStream Plugins catalog
requires a separate catalog update.

WizeStream pins the signing key on first installation. Updates must keep both the plugin ID and
publisher key and increase `versionCode`. Uninstalling does not reset that publisher pin.

## Build and test

### Toolchain

- JDK **21**. CI uses Temurin **21.0.12+8**.
- Gradle **9.5.1**, using the WizeStream checkout's wrapper.
- Android SDK platform **37.0** and Build Tools **36.1.0** for the host build.
- A `wizestream-plugin-api.jar` exported from the pinned WizeStream checkout.
- Standalone **R8/D8 8.13.19**, resolved automatically by this plugin's Gradle build.

The CI and release host baseline is commit
[`68432a57853aa2c0ffdbe4f7c163c2fedd01c18e`](https://github.com/wizdom13/WizeStream/commit/68432a57853aa2c0ffdbe4f7c163c2fedd01c18e)
(WizeStream 1.52.4). The plugin keeps its pinned standalone D8 compiler to support Kotlin **2.3.21**
metadata. Its Android API and NIO desugaring profile remain aligned with the host SDK.

Set `ANDROID_HOME` to the SDK installation. Alternatively, add an untracked `local.properties`
containing `sdk.dir=/absolute/path/to/android-sdk` to both projects. On Windows, use forward
slashes in that properties-file path, for example `sdk.dir=C:/Android/Sdk`.

Keep sibling checkouts named `WizeStream` and `PornHub-WizeStream-Plugin`. This repository uses
the host wrapper and does not include another Gradle wrapper.

### Linux or macOS

From the directory containing both repositories:

```sh
git clone --branch pipe https://github.com/wizdom13/WizeStream.git
git clone https://github.com/wizdom13/PornHub-WizeStream-Plugin.git
git -C WizeStream checkout 68432a57853aa2c0ffdbe4f7c163c2fedd01c18e
cd WizeStream
./gradlew :app:pluginSdkJar -DskipFormatKtlint --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx4096M --add-opens jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED'
plugin_sdk_jar="$PWD/app/build/outputs/plugin-sdk/wizestream-plugin-api.jar"
./gradlew -p ../PornHub-WizeStream-Plugin \
  "-PpluginSdkJar=$plugin_sdk_jar" test pluginPackage
```

If the repositories already exist, use those checkouts rather than cloning again. Export the
SDK from the pinned host revision before building the plugin.

### Windows PowerShell

From the directory containing both repositories, using existing checkouts if available:

```powershell
git clone --branch pipe https://github.com/wizdom13/WizeStream.git
git clone https://github.com/wizdom13/PornHub-WizeStream-Plugin.git
git -C WizeStream checkout 68432a57853aa2c0ffdbe4f7c163c2fedd01c18e
Set-Location .\WizeStream
.\gradlew.bat :app:pluginSdkJar -DskipFormatKtlint --max-workers=2 `
  '-Dorg.gradle.jvmargs=-Xmx4096M --add-opens jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED'
if ($LASTEXITCODE -ne 0) {
    throw "Plugin SDK export failed."
}
$pluginSdkJar = (Resolve-Path .\app\build\outputs\plugin-sdk\wizestream-plugin-api.jar).Path
.\gradlew.bat -p ..\PornHub-WizeStream-Plugin "-PpluginSdkJar=$pluginSdkJar" test pluginPackage
```

The unsigned package is written to
`PornHub-WizeStream-Plugin/build/repo/pornhub-wizestream-4.ppp`.
The filename uses `versionCode`, not the display version.

### What CI verifies

[CI](.github/workflows/ci.yml) exports the actual SDK from the pinned host, runs JVM fixture
tests, compiles Android DEX, and verifies a test-only package signature. The tests cover URL
identity, uploader navigation, channel metadata and video tabs, catalog parsing and pagination,
media sources, subtitles, request headers, and unavailable or restricted responses. Pagination
is also tested with a fresh extractor, matching how the host loads subsequent channel pages.
All provider responses used by the tests are synthetic; no live service response is required.
Request tests exercise successful search and Videos loading through the asynchronous host API,
stalled callbacks, cancellation through a real host `CancellableCall`, late callbacks, error
propagation and thread interruption. The test transport handles never make HTTP requests.

The `.ppp` packaging check verifies that the archive contains its manifest and DEX and excludes
replacement copies of host-provided classes. CI uploads the test package and JVM test reports.

**There are no Android emulator jobs in this repository's CI.** Compiling DEX does not establish
runtime loading compatibility on Android. Loading through the host on API 24 and a minified
release build, and successful live playback on a real device, remain unverified. Before
publishing, check the exact signed package in a compatible WizeStream installation: search,
pagination, channel opening from video details and cards, further pages of channel videos,
permitted public playback, quality selection, supplied subtitles, link opening and restricted-resource
errors.

## Sign and publish a release

### Automatic GitHub releases

[`.github/workflows/release.yml`](.github/workflows/release.yml) publishes after **PornHub plugin
CI** succeeds for a push to `main`. It checks out that exact commit and uses the same pinned host
SDK, JDK, standalone R8/D8 version and Android compiler profile as CI. Pull requests run CI and
workflow validation; publication and the signing secret are restricted to eligible main-branch
release runs.

The release workflow builds and tests the plugin, signs it through `repositoryIndex`, and creates
or updates the rolling **`repo`** release. It retains older versioned `.ppp` assets, uploads the
current package first, and updates `index.json` last. An identical package can be reused on a
rerun. A changed package requires a higher `versionCode`; publisher-key changes and version
rollbacks are rejected. A newly created release remains a draft until its assets are uploaded.

Only one repository secret is required: **`SIGNING_KEY`**, containing the **Base64 encoding of an
Ed25519 PKCS#8 DER private key**. The workflow derives the matching X.509 DER public key, verifies
the generated package signature, and removes temporary key files afterward. Its GitHub token is
supplied automatically; no personal access token is needed for publication.

### 1. Create the persistent publisher key

Use **JDK 21** and sibling checkouts named `WizeStream` and `PornHub-WizeStream-Plugin`. Key
generation uses the host Gradle wrapper but does **not** require an exported plugin SDK or Android
SDK. First-time Gradle and plugin downloads still require network access.

Run these commands from the **PornHub-WizeStream-Plugin checkout**. They store the key pair
outside the repository. If this plugin already has a publisher key pair, reuse those files and
adjust the paths instead of creating another identity. `generateSigningKey` reuses a complete
existing pair and refuses to replace an incomplete pair.

#### Windows PowerShell

```powershell
$pornhubKeyDir = Join-Path $env:USERPROFILE "WizeStream-signing\PornHub"
$pornhubPrivateKey = Join-Path $pornhubKeyDir "signing-key.pk8"
$pornhubPublicKey = Join-Path $pornhubKeyDir "public-key.der"

..\WizeStream\gradlew.bat -p . `
  "-PpluginSigningKey=$pornhubPrivateKey" `
  "-PpluginPublicKey=$pornhubPublicKey" `
  generateSigningKey

if ($LASTEXITCODE -ne 0) {
    throw "Signing key creation failed."
}

[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes($pornhubPrivateKey)
) | Set-Clipboard
```

The final command copies the exact secret value to your clipboard without printing it.

#### Linux or macOS

```sh
pornhub_key_dir="$HOME/WizeStream-signing/PornHub"

../WizeStream/gradlew -p . \
  "-PpluginSigningKey=$pornhub_key_dir/signing-key.pk8" \
  "-PpluginPublicKey=$pornhub_key_dir/public-key.der" \
  generateSigningKey
```

After key generation succeeds, encode the private file:

```sh
base64 < "$pornhub_key_dir/signing-key.pk8" | tr -d '\r\n'
```

Copy the resulting Base64 value. Encode the **private** file's binary bytes directly; use neither
the public key, a file path nor PEM header/footer text as the secret value.

### 2. Add the GitHub repository secret

Open this repository's **Settings → Secrets and variables → Actions → Secrets → New repository
secret**. Set the name to **`SIGNING_KEY`**, paste the Base64 value, and click **Add secret**.
The workflow reads it as `${{ secrets.SIGNING_KEY }}`; the secret's name is `SIGNING_KEY`.

If your GitHub CLI is already authenticated, this Linux/macOS alternative stores the value
directly without printing it:

```sh
base64 < "$pornhub_key_dir/signing-key.pk8" |
  tr -d '\r\n' |
  gh secret set SIGNING_KEY --app actions --repo wizdom13/PornHub-WizeStream-Plugin
```

Keep a secure backup of **both** key files and reuse this pair for every release. Keep the private
key and its Base64 value out of commits, issues and chat messages. CI's disposable test keys must
not become the publisher identity.

### 3. Publish and update

Merge the release workflow into `main` and configure the secret. A successful CI run for a push
to `main` then starts publication automatically. To publish after adding the secret or recover
from a failed publication, open **Actions → Release PornHub plugin → Run workflow** and select
**main**. Manual publication also requires successful **PornHub plugin CI** for the current main
commit.

For a changed plugin, increase `versionCode` and update `versionName` in
`src/main/resources/plugin.json` before merging. Retain the same `SIGNING_KEY`. The workflow skips
superseded commits and serializes publishers so concurrent runs cannot replace the index out of
order. Missing or malformed signing material stops publication.

If replacing the index fails, the workflow attempts to restore the previous index and saves that
previous signed index as a workflow artifact for recovery. Published package assets are never
overwritten or deleted. A rerun can remove a verified empty `starter` placeholder left by a
failed upload; an incomplete index can be recovered this way only while its release is still
a draft.

Public installation additionally requires publicly accessible release assets, as described in
[Install](#install). Publication does not change this repository's private visibility.

### Local signing and publishing

You can also build a signed repository locally. From the WizeStream checkout, after exporting the
SDK, use the same permanent key pair. For example, with the Linux/macOS key location above:

```sh
plugin_sdk_jar="$PWD/app/build/outputs/plugin-sdk/wizestream-plugin-api.jar"
pornhub_key_dir="$HOME/WizeStream-signing/PornHub"
./gradlew -p ../PornHub-WizeStream-Plugin \
  "-PpluginSdkJar=$plugin_sdk_jar" \
  "-PpluginSigningKey=$pornhub_key_dir/signing-key.pk8" \
  "-PpluginPublicKey=$pornhub_key_dir/public-key.der" \
  repositoryIndex
```

The private key is PKCS#8 DER; the public key file is X.509 DER. For subsequent releases, retain
the same keys, increase the manifest's `versionCode`, update `versionName`, and run
`repositoryIndex` without generating another identity.

The build signs the **raw 32-byte SHA-256 digest** of the exact package with Ed25519 and verifies
the signature before producing `build/repo/index.json`. The index preserves **`nsfw: true`**.
Publish the index and matching `.ppp` together in the same HTTPS directory. The index uses a
relative package filename. To copy its entry into another catalog, replace only the package
location with the absolute HTTPS download URL while retaining the generated digest, signature,
public key, NSFW flag and compatibility fields. Do not modify a signed archive.

## Implementation notes

- Kotlin sources live under `org.wizestream.plugin.pornhub`; the native entry class is
  `PornHubPlugin` and the service name is **PornHub**.
- Preserve `org.wizestream.plugin.pornhub` as the plugin ID so history and saved local playlist
  items continue to refer to the same service. The host assigns the numeric service ID.
- Keep `pluginMinSdk=23`, standalone R8/D8 **8.13.19** and the pinned NIO desugaring configuration.
  The independent installation minimum remains Android API 24.
- The SDK, Kotlin standard library, nanojson, jsoup and commons-lang3 remain `compileOnly`.
  JVM tests add them to the test runtime; the distributed `.ppp` contains no replacement host classes.
- The plugin uses the host downloader and does not store credentials or add a separate networking
  client. Native API 2.0 provides profile context and preferences for future features that need them.

## Source references and license

- [Pinned WizeStream native plugin guide](https://github.com/wizdom13/WizeStream/blob/68432a57853aa2c0ffdbe4f7c163c2fedd01c18e/docs/plugins.md)
- [WizeStream Plugin Template](https://github.com/wizdom13/WizeStream-Plugin-Template)
- [yt-dlp provider extractor](https://github.com/yt-dlp/yt-dlp/blob/83564f85db7507486fbe3b0d0e72498f31ab0600/yt_dlp/extractor/pornhub.py)
- [Pornhub.js search parser](https://github.com/pionxzh/Pornhub.js/blob/7c0f6a85aeb5ab108cc66c29242d4fa7a0310039/src/scrapers/search/video.ts)

The provider sources document the public data shapes used to design the independent Kotlin
parser. Fixtures contain only synthetic neutral content.

**GPL-3.0-or-later.** See [LICENSE](LICENSE). This repository started from the WizeStream Plugin
Template. The packaging format and original service skeleton derive from the
[PipePipe Plugin Template](https://github.com/InfinityLoop1308/PipePipePluginTemplate/tree/91950887dfd173263ecacfc466437a33777d242d);
native SDK integration and signing are WizeStream additions. This community plugin is not an
official PornHub product.
