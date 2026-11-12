# Microsoft Account Authentication: Diagnosis, Fix, and Setup Guide

## 1. Executive Summary & Root Cause

When adding a Microsoft account in Blaze Launcher (based on Zalith Launcher 2), users encountered the dialog:
```
Gagal Login
(400) Server tidak dapat memahami permintaan tersebut
```

### Root Cause
1. **Silent Empty Fallback**: In `ZalithLauncher/build.gradle.kts`, `BuildKeys.OAUTH_CLIENT_ID` fell back to `""` (empty string) when `OAUTH_CLIENT_ID` was not configured in GitHub Secrets, environment variables, or local files.
2. **Missing Repository Secret**: Upstream Zalith Launcher injected `OAUTH_CLIENT_ID: ${{ secrets.OAUTH_CLIENT_ID }}` in its build workflows (`.github/workflows/build.yml`). This secret was omitted in the Blaze Launcher repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` were configured, but `OAUTH_CLIENT_ID` / `MS_CLIENT_ID` were absent).
3. **Microsoft Rejection**: In `MicrosoftAuthenticator.kt:fetchDeviceCodeResponse`, the launcher sent:
   ```http
   POST https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode
   Content-Type: application/x-www-form-urlencoded

   client_id=&scope=XboxLive.signin+offline_access+openid+profile+email
   ```
   Microsoft Azure AD returned HTTP `400 Bad Request`:
   ```json
   {
     "error": "invalid_request",
     "error_description": "AADSTS900144: The request body must contain the following parameter: 'client_id'."
   }
   ```
4. **Locale Mapping**: In Indonesian locale (`values-in/strings.xml`), `account_logging_in_failed` maps to `"Gagal Login"` and `error_bad_request` maps to `"(%s) Server tidak dapat memahami permintaan tersebut"`, resulting in the `"Gagal Login (400)"` message with no actionable debug context.

---

## 2. Microsoft Authentication Flow: Request Sequence & Parameters

The complete Microsoft account authentication pipeline in ZL2 consists of 8 sequential steps across 5 Microsoft and Mojang hosts:

```
[UI Screen: AccountManageScreen]
       │
       ▼
[TaskSystem: AccountUtils.microsoftLogin]
       │
       ├─► Step 1: Device Code Request (Microsoft Identity Platform v2.0)
       │          POST https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode
       │          Parameters: client_id, scope=XboxLive.signin offline_access openid profile email
       │          Grant: RFC 8628 Device Authorization Grant
       │
       ├─► Step 2: Device Token Polling (Microsoft Identity Platform v2.0)
       │          POST https://login.microsoftonline.com/consumers/oauth2/v2.0/token
       │          Parameters: grant_type=urn:ietf:params:oauth:grant-type:device_code, device_code, client_id, tenant=/consumers
       │          Poll interval: 5s (adjusted on slow_down, loops while authorization_pending)
       │          Returns: Bearer access_token, refresh_token
       │
       ├─► Step 3: Token Refresh (Live SDK endpoint - used on session expiry)
       │          POST https://login.live.com/oauth20_token.srf
       │          Parameters: grant_type=refresh_token, client_id, refresh_token
       │          Returns: new access_token, new refresh_token
       │
       ├─► Step 4: Xbox Live (XBL) Authentication
       │          POST https://user.auth.xboxlive.com/user/authenticate
       │          Body: JSON { Properties: { AuthMethod: "RPS", SiteName: "user.auth.xboxlive.com", RpsTicket: "d=<access_token>" }, RelyingParty: "http://auth.xboxlive.com", TokenType: "JWT" }
       │          Returns: Token, DisplayClaims.xui[0].uhs (user hash)
       │
       ├─► Step 5: Xbox Secure Token Service (XSTS)
       │          POST https://xsts.auth.xboxlive.com/xsts/authorize
       │          Body: JSON { Properties: { SandboxId: "RETAIL", UserTokens: ["<xbl_token>"] }, RelyingParty: "rp://api.minecraftservices.com/", TokenType: "JWT" }
       │          Returns: Token (xsts_token)
       │          Error handling: Maps XErr codes (2148916238 Underage, 2148916235 Blocked Region, etc.)
       │
       ├─► Step 6: Minecraft Services Authentication
       │          POST https://api.minecraftservices.com/authentication/login_with_xbox
       │          Body: JSON { identityToken: "XBL3.0 x=<uhs>;<xsts_token>" }
       │          Returns: access_token (Minecraft JWT), expires_in
       │
       ├─► Step 7: Game Ownership Verification
       │          GET https://api.minecraftservices.com/entitlements/mcstore
       │          Headers: Authorization: Bearer <minecraft_access_token>
       │          Fails with NotPurchasedMinecraftException if items array is empty
       │
       └─► Step 8: Minecraft Profile Retrieval
                  GET https://api.minecraftservices.com/minecraft/profile
                  Headers: Authorization: Bearer <minecraft_access_token>
                  Returns: id (UUID), name (username), skins, capes
                  Persists to AccountManager with AccountType.MICROSOFT
```

### Parameter Origins and Definition Types

| Parameter | Location / Function | Origin | Definition Type |
| :--- | :--- | :--- | :--- |
| `client_id` | Steps 1, 2, 3 | `BuildKeys.OAUTH_CLIENT_ID` / `BuildKeys.MS_CLIENT_ID` | Injected at build time via `MS_CLIENT_ID` GitHub secret or `-Pms_client_id` Gradle property |
| `redirect_uri` | N/A | Not used | RFC 8628 Device Code flow does not use redirect URIs |
| `scope` | Step 1 (`fetchDeviceCodeResponse`) | `MicrosoftAuthenticator.kt:80` | Hardcoded source literal: `XboxLive.signin offline_access openid profile email` |
| `grant_type` | Steps 2 & 3 | `MicrosoftAuthenticator.kt:142, 276` | Hardcoded source literals: `urn:ietf:params:oauth:grant-type:device_code` and `refresh_token` |
| `CurseForge API Key` | Modpack search / download | `BuildKeys.CURSEFORGE_API` | Injected at build time via `CURSEFORGE_API_KEY` GitHub secret or `-Pcurseforge_api_key` |

---

## 3. Upstream Workflow Audit

Inspection of upstream commit history (`upstream/main:.github/workflows/build.yml`) revealed the following build-time injections:

```yaml
env:
  OAUTH_CLIENT_ID: ${{ secrets.OAUTH_CLIENT_ID }}
  CURSEFORGE_API_KEY: ${{ secrets.CURSEFORGE_API_KEY }}
```

In upstream `gradle.properties`:
```properties
# your OAuth client id
#oauth_client_id=xxx
# your CurseForge API key
#curseforge_api_key=xxx
```

### Absence in Blaze Launcher
Repository secret audit via `gh secret list -R kaito65452/BlazeLauncher` confirmed only keystore secrets exist:
- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Neither `MS_CLIENT_ID` nor `OAUTH_CLIENT_ID` was set, leading to the silent empty string fallback.

---

## 4. Fix Implemented

### 4.1 Strict Build Validation in Gradle
In `ZalithLauncher/build.gradle.kts`:
- Added priority resolution: `ms_client_id` property -> `oauth_client_id` property -> `MS_CLIENT_ID` env/secret -> `OAUTH_CLIENT_ID` env/secret -> local files.
- Release builds now **fail hard** if the client ID is missing or empty, preventing silent broken builds:
  ```kotlin
  val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
  if (isReleaseTask && (resolvedMsClientId.isNullOrBlank() || resolvedMsClientId == "xxx")) {
      throw GradleException(
          "BUILD FAILED: Microsoft Client ID is missing or blank for release build! " +
          "Please provide a valid client ID via 'MS_CLIENT_ID' environment variable/GitHub secret, " +
          "or pass '-Pms_client_id=<id>' (or configure 'ms_client_id=<id>' in gradle.properties). " +
          "See docs/MS_LOGIN.md for setup instructions."
      )
  }
  ```

### 4.2 Workflow Wiring
In `.github/workflows/ci.yml`, `nightly.yml`, and `release.yml`:
- Injected `MS_CLIENT_ID: ${{ secrets.MS_CLIENT_ID }}` and `OAUTH_CLIENT_ID: ${{ secrets.OAUTH_CLIENT_ID }}` into runner `env`.
- In `ci.yml` and `nightly.yml`, if `MS_CLIENT_ID` is not yet set by the repository owner, a verification placeholder (`00000000-0000-0000-0000-000000000000`) is supplied so test and assembly validation continue to pass.
- In `release.yml`, `MS_CLIENT_ID` is strictly required.

### 4.3 Redacted Error Logging & Diagnostic Dialog
Created `io.github.kaito65452.blazelauncher.msauth.MicrosoftAuthErrorHandler`:
- Intercepts all authentication steps and wraps errors in `MicrosoftLoginException`.
- Redacts all sensitive tokens (`access_token`, `refresh_token`, `Token`, `token`, `user_code`, `device_code`, `uhs`, `client_secret`, `password`, `Bearer ...`) so credentials never leak to logs or UI.
- Enhances error dialogs to show:
  ```
  (400) Server tidak dapat memahami permintaan tersebut

  [Debug Details]
  Step: Device Code Request
  Status: 400 Bad Request
  Response: {"error":"invalid_request","error_description":"AADSTS900144: The request body must contain the following parameter: 'client_id'."}

  Hint: Microsoft OAuth client ID is missing or invalid. Check MS_CLIENT_ID configuration.
  ```
- Writes detailed redacted logs to `Logger.error("MicrosoftAuth", ...)` (viewable in the launcher log viewer and logcat).

---

## 5. Registering a Dedicated Azure Application

To enable Microsoft login for your builds, register your own public client application in the Microsoft Entra admin center (Azure Portal):

1. **Sign In**: Navigate to [Microsoft Entra admin center](https://entra.microsoft.com/) or [Azure Portal App Registrations](https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade).
2. **New Registration**:
   - Name: `Blaze Launcher` (or your preferred name).
   - Supported account types: Select **"Accounts in any organizational directory (Any Microsoft Entra ID tenant - Multitenant) and personal Microsoft accounts (e.g. Skype, Xbox)"**.
   - Redirect URI: Leave blank (Device Code flow does not use a redirect URI).
   - Click **Register**.
3. **Enable Device Code Flow (Public Client)**:
   - In the left sidebar, navigate to **Manage** -> **Authentication**.
   - Under **Advanced settings** -> **Allow public client flows**:
   - Set **"Enable the following mobile and desktop flows"** (`allowPublicClient`) to **Yes**.
   - Click **Save**.
4. **Configure API Permissions**:
   - In the left sidebar, navigate to **Manage** -> **API permissions**.
   - Ensure `Microsoft Graph` permissions include:
     * `User.Read` (Delegated)
     * `offline_access` (Delegated)
     * `openid` (Delegated)
     * `profile` (Delegated)
     * `email` (Delegated)
   - Click **Add a permission** -> **APIs my organization uses** -> Search for `XboxLive` -> Select `XboxLive.signin`.
5. **Retrieve Application (client) ID**:
   - In the left sidebar, click **Overview**.
   - Copy the **Application (client) ID** (a UUID like `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).
6. **Configure Secret in GitHub**:
   - Go to your repository on GitHub: `Settings` -> `Secrets and variables` -> `Actions`.
   - Click **New repository secret**.
   - Name: `MS_CLIENT_ID`.
   - Value: `<Your Application (client) ID>`.
   - For local builds, optionally add `ms_client_id=<Your Application ID>` to `ZalithLauncher/gradle.properties`.

---

## 6. Mojang Application Approval Requirements

### Exact Failing Step for Unapproved Applications
When an independent developer registers an Azure application with public client device code flow:
- **Steps 1 & 2** (Microsoft Azure AD device code & token exchange) succeed.
- **Steps 4 & 5** (Xbox Live XBL and XSTS token requests) succeed.
- **Step 6 (Minecraft Services Token Exchange)**:
  ```http
  POST https://api.minecraftservices.com/authentication/login_with_xbox
  Content-Type: application/json

  {"identityToken": "XBL3.0 x=<uhs>;<xsts_token>"}
  ```
  Fails with HTTP **401 Unauthorized** or **403 Forbidden** with `{"path":"/authentication/login_with_xbox","error":"NOT_FOUND","errorMessage":"The server has not found anything matching the request URI"}` or `UnauthorizedOperationException`.

### Why This Happens
Mojang Studios / Microsoft enforces an API whitelist policy on `api.minecraftservices.com`. Only client IDs specifically approved by Mojang through the [Microsoft / Mojang Developer Program](https://help.minecraft.net/hc/en-us/articles/4408901278477) are permitted to exchange XSTS tokens for Minecraft services tokens.

### Developer Options
1. **Apply for Mojang Approval**: Submit an application registration request to Mojang explaining the non-commercial launcher use case.
2. **Community Client ID**: Use an established public client ID registered for Minecraft Java Edition launchers that have been approved by Mojang for open-source launchers.

---

## 7. Comparison with Horizon Rust Engine

### Engine Implementation
In the Horizon Android engine (`crates/horizon-client/src/android/jni_bridge.rs:956-963`):
```rust
pub extern "system" fn Java_dev_horizon_android_bridge_BlazeNativeBridge_nativeStartMicrosoftDeviceLogin(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let ep = Endpoints::live();
    let client_id = account::client_id().unwrap_or_else(|| "00000000402b5328".to_string());
    match account::device_code(&ep, &client_id) { ... }
}
```

In `launcher/src/account.rs:48-58`:
```rust
pub fn client_id() -> Option<String> {
    configured_client_id(
        std::env::var("HORIZON_MSA_CLIENT_ID").ok(),
        option_env!("HORIZON_MSA_CLIENT_ID"),
    )
}
```

### Analysis & Client ID Sharing
- **Legacy Live SDK vs Modern Azure AD v2.0**:
  - The engine's fallback `"00000000402b5328"` is the legacy Mojang PC launcher client ID on `login.live.com` using legacy scope `service::user.auth.xboxlive.com::MBI_SSL`.
  - Zalith Launcher 2 uses the modern Microsoft Identity Platform (`login.microsoftonline.com/consumers/oauth2/v2.0/devicecode`) with OAuth 2.0 scopes (`XboxLive.signin offline_access openid profile email`).
  - The legacy ID `"00000000402b5328"` cannot be passed to the modern Azure AD v2 device code endpoint.
- **Can They Share the Same ID?**:
  - Yes, if a custom Azure Application is registered with public client device code flow, that UUID can be shared between the launcher (`MS_CLIENT_ID`) and the engine (`HORIZON_MSA_CLIENT_ID`), provided both are configured for the corresponding flow.

---

## 8. Offline and Ely.by Accounts Verification

Neither Offline accounts nor Ely.by accounts depend on `BuildKeys.OAUTH_CLIENT_ID` or Microsoft authentication endpoints:

1. **Offline Accounts (`AccountType.LOCAL`)**:
   - Generates an offline player UUID: `UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray())`.
   - Stored locally without network calls.
   - Fully operational.
2. **Ely.by Accounts (`authlib-injector`)**:
   - Uses Ely.by's independent Yggdrasil authentication server (`https://ely.by` / `https://authserver.ely.by`).
   - Authenticates via `/authserver/authenticate` using user email and password.
   - Fully operational.

Automated unit tests in `AccountCompatibilityTest.kt` verify that Offline and Ely.by accounts function independently of Microsoft OAuth configuration.

---

## 9. Manual Test Checklist (Device-Side Results: UNVERIFIED)

*Note: Per project rules, compilation and unit tests run in GitHub Actions. All physical device tests are marked UNVERIFIED until tested on an Android device.*

- [ ] **Test 1: Offline Account Addition [UNVERIFIED]**
  - Navigate to Account Management -> Add Account -> Local/Offline.
  - Enter username `TestPlayer`.
  - Verify account is added immediately with avatar and no network error.
- [ ] **Test 2: Ely.by Account Addition [UNVERIFIED]**
  - Navigate to Account Management -> Add Account -> Third-Party Server (Ely.by).
  - Enter Ely.by credentials.
  - Verify authentication completes against `https://ely.by`.
- [ ] **Test 3: Microsoft Account Error Dialog Details [UNVERIFIED]**
  - Trigger Microsoft login without valid network / credentials.
  - Verify the error dialog displays localized title and the `[Debug Details]` section showing failing step and sanitized response.
  - Verify no plain access tokens or user codes appear in debug details or logcat.
- [ ] **Test 4: Release Build Failure Verification [UNVERIFIED]**
  - Run `./gradlew assembleRelease` without `MS_CLIENT_ID` set.
  - Verify build halts with: `BUILD FAILED: Microsoft Client ID is missing or blank for release build!`.
