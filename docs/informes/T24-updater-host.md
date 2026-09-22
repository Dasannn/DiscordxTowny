# T24 — The updater can reach the release it finds

**Task**: T24  
**Branch**: `fix/updater-delivery-host`  
**Zone**: `src/main/java/com/discordtowny/update/`, `src/test/java/com/discordtowny/update/`, and the `updates:` section of `messages_en.yml` and `messages_es.yml`.

---

## 1. Summary of Changes

### 1.1 Catalogs (`src/main/resources/messages_en.yml` and `messages_es.yml`)
- Added `updates.check-failed` under the `updates:` block in both files:
  - `messages_en.yml`: `check-failed: "&cCould not check for updates: {reason}."`
  - `messages_es.yml`: `check-failed: "&cNo se pudo comprobar si hay actualizaciones: {reason}."`
- Formatted consistently with neighboring keys (`updates.download-failed`, `updates.checksum-mismatch`, `updates.up-to-date`).

### 1.2 `UpdateSourcePolicy.java`
- **Replaced loose/broken path restriction with strict delivery host allowlist**:
  - Maintained `OFFICIAL_OWNER = "Dasannn"` and `OFFICIAL_REPO = "DiscordxTowny"`.
  - Added exact allowlist constant:
    ```java
    public static final Set<String> ALLOWED_DELIVERY_HOSTS = Set.of(
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com"
    );
    ```
  - Replaced legacy path regex (`/Dasannn/DiscordxTowny/...`) in `isAllowedDeliveryRedirectUri` with an exact host check. Modern GitHub release asset delivery redirects to paths containing numeric IDs (`/github-production-release-asset/<numeric-id>/...`) and SAS tokens that never match the repository name in the path.
- **Strict origin & hop separation**:
  - `isAllowedInitialUri(URI)` / `validateInitialUri(URI)`: Initial requests MUST target the official repository on `api.github.com` (`/repos/Dasannn/DiscordxTowny/releases...`) or `github.com` (`/Dasannn/DiscordxTowny/releases/download/...`). Direct requests to delivery hosts without provenance are strictly rejected upfront.
  - `isAllowedRedirectDestination(URI)` / `validateRedirectDestination(URI)`: Authorizes redirect hops strictly if the target is an official API/asset URI or one of the exact delivery hosts (`ALLOWED_DELIVERY_HOSTS`) over HTTPS on port 443 with no user-info.
  - Suffix matching (`*.githubusercontent.com`) is strictly prohibited, preventing attacker subdomains from masquerading as GitHub delivery nodes.

### 1.3 `JdkHttpTransport.java`
- Validates the initial URI with `UpdateSourcePolicy.validateInitialUri(uri)`.
- Validates every redirect hop destination with `UpdateSourcePolicy.validateRedirectDestination(nextUri)` before following.
- Bounded redirect chain depth: limits redirects to `MAX_REDIRECTS = 5` hops; exceeding this throws `IOException`.
- Validates final URI after redirect resolution.
- Safely handles malformed `Location` headers by catching `IllegalArgumentException` and translating to `IOException`.

### 1.4 `UpdateService.java`
- Extended `UpdateService` interface with default contract methods:
  - `default boolean isLastCheckFailed()`: indicates if the most recent check failed to complete.
  - `default Optional<String> getLastCheckError()`: returns the error message / reason of the last failed check.
  - `enum CheckStatus`: `UP_TO_DATE`, `UPDATE_AVAILABLE`, `CHECK_FAILED`, `NOT_CHECKED`.
  - `default CheckStatus checkStatus()`: provides tri-state update status checking.

### 1.5 `DefaultUpdateService.java`
- Added atomic state fields: `AtomicBoolean lastCheckFailed`, `AtomicReference<String> lastCheckError`, and `AtomicBoolean hasCheckedAtLeastOnce`.
- Implemented `isLastCheckFailed()`, `getLastCheckError()`, and `checkStatus()`.
- Implemented `renderStatusMessages(Messages msg)`:
  - Distinguishes all states: pending restart, update available, check failed, and up to date.
  - When the last check failed, renders `updates.check-failed` with the failure reason; **never** emits `updates.up-to-date`.
- Updated `doCheckForUpdate()`:
  - Records failure reasons and sets `lastCheckFailed = true` for network failures, rate limiting, unexpected HTTP status codes, missing or invalid assets, and missing checksums.
  - Clears `lastCheckFailed = false` and `lastCheckError = null` when a check succeeds (both when up to date / 304 Not Modified, and when an update is available).
  - Preserved `networkErrorLogged` deduplication to prevent console spam across repeated failures.

---

## 2. Fulfillment of Contract and Acceptance Criteria

| Requirement / Constraint | How It Is Satisfied |
|---|---|
| **Delivery redirect path no longer requires repository name** | `UpdateSourcePolicy.isAllowedDeliveryRedirectUri` verifies the exact host (`release-assets.githubusercontent.com` or `objects.githubusercontent.com`) and HTTPS, dropping the obsolete path pattern that caused the failure. |
| **Strict provenance: chain starts at official repository** | `UpdateSourcePolicy.validateInitialUri` ensures metadata and asset downloads originate exclusively at `api.github.com/repos/Dasannn/DiscordxTowny` or `github.com/Dasannn/DiscordxTowny`. |
| **Exact host allowlist (no suffix matching)** | `ALLOWED_DELIVERY_HOSTS = Set.of("release-assets.githubusercontent.com", "objects.githubusercontent.com")`. Suffixes like `evil-githubusercontent.com` or `attacker.githubusercontent.com` are rejected. |
| **HTTPS enforced throughout** | `isBasicHttpsValid` mandates `https` scheme, port 443 (or default -1), and `userInfo == null` on initial and all redirect hops. |
| **Bounded redirect chain** | `JdkHttpTransport` enforces `MAX_REDIRECTS = 5`. Loops or long redirect chains throw an `IOException`. |
| **Checksum verification remains mandatory** | Releases without valid 64-hex SHA-256 are rejected. Downloaded files failing SHA-256 match are discarded with zero remnants in `update/` or temp dirs; active jar is never touched. |
| **Check that could not run is never reported as up to date** | `isLastCheckFailed()`, `checkStatus() == CHECK_FAILED`, and `renderStatusMessages()` emit `updates.check-failed` (`Could not check for updates: {reason}`) in the user's language. Success is never falsely claimed. |
| **Console log deduplication preserved** | `networkErrorLogged` suppresses repeated warning logs for consecutive failures until recovery. |

---

## 3. Unit Test Verification

Added test methods in `src/test/java/com/discordtowny/update/DefaultUpdateServiceTest.java`:

1. `updateSourcePolicyEnforcesExactDeliveryHostsOnRedirectOnly()`:
   - Verifies initial requests to official repo are allowed.
   - Verifies redirects to `release-assets.githubusercontent.com` with numeric ID paths and signed query tokens are accepted.
   - Verifies redirects to `objects.githubusercontent.com` are accepted.
   - Verifies direct initial requests to delivery hosts without provenance are strictly rejected.
   - Verifies untrusted hosts (`evil-githubusercontent.com`, suffix attacker, plain HTTP, user-info) are rejected.
2. `jdkHttpTransportFollowsRedirectToDeliveryHosts()`:
   - Verifies transport follows 302 redirect from official GitHub download URL to `release-assets.githubusercontent.com` and delivers payload.
3. `jdkHttpTransportLimitsRedirectChain()`:
   - Verifies transport rejects redirect loops exceeding 5 hops with `Too many redirects`.
4. `fullDownloadSucceedsThroughReleaseAssetsRedirectWithValidChecksum()`:
   - End-to-end test simulating release discovery and downloading both `.sha256` and `.jar` redirected to delivery hosts. Verified jar is staged to `update/DiscordTowny.jar` with matching SHA-256 and active jar untouched.
5. `checksumMismatchAfterDeliveryDiscardsDownloadWithoutRemnants()`:
   - Verifies that delivery from `release-assets.githubusercontent.com` with an invalid/mismatched checksum results in `CHECKSUM_MISMATCH`, zero remnants in `update/`, and untouched active jar.
6. `failedCheckMarksStateAndRendersCheckFailedNeverUpToDate()`:
   - Verifies that when a check fails (e.g., network error or policy rejection), `isLastCheckFailed()` is `true`, `checkStatus()` is `CHECK_FAILED`, duplicate console warnings are silenced, and status output renders `updates.check-failed` with the error reason in English and Spanish, and **never** renders `updates.up-to-date`.
7. `recoveryAfterFailedCheckClearsErrorState()`:
   - Verifies that after a failed check, a subsequent successful check resets `isLastCheckFailed()` to `false`, clears error state, and transitions status back to `UP_TO_DATE`.

---

## 4. Boundary Notice for the Architect (Outside Exclusive Zone)

The task instructions specify an exclusive zone:
- `src/main/java/com/discordtowny/update/`
- `src/test/java/com/discordtowny/update/`
- `updates:` section of `messages_en.yml` and `messages_es.yml`

In accordance with rule "Touch nothing else. If you need something outside it, say so in your report", the in-game command handler in `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java` was **not** modified directly.

To wire the newly added `isLastCheckFailed()` check into the `/dt admin update status` command handler, the architect can apply the following small patch:

### Proposed Patch for `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java`

```diff
--- a/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java
+++ b/src/main/java/com/discordtowny/minecraft/MinecraftCommands.java
@@ -1106,6 +1106,10 @@ public final class MinecraftCommands {
                         if (!summary.isBlank()) {
                             sender.sendMessage(msg.get("updates.summary", Map.of("summary", summary)));
                         }
+                    } else if (updateService.isLastCheckFailed()) {
+                        String reason = updateService.getLastCheckError()
+                                .orElseGet(() -> msg.label("general.unknown"));
+                        sender.sendMessage(msg.get("updates.check-failed", Map.of("reason", reason)));
                     } else {
                         sender.sendMessage(msg.get("updates.up-to-date"));
                     }
```

### Proposed Test for `src/test/java/com/discordtowny/minecraft/MinecraftCommandsTest.java`

```java
    @Test
    void adminUpdateStatusReportsCheckFailedWhenLastCheckFailed() throws Exception {
        UpdateService updateService = mock(UpdateService.class);
        when(updateService.currentVersion()).thenReturn("1.0.0");
        when(updateService.isUpdatePending()).thenReturn(false);
        when(updateService.getAvailableUpdate()).thenReturn(Optional.empty());
        when(updateService.isLastCheckFailed()).thenReturn(true);
        when(updateService.getLastCheckError()).thenReturn(Optional.of("could not reach GitHub"));

        LiteralCommandNode<CommandSourceStack> root = createRoot(updateService);
        Player admin = mock(Player.class);
        when(admin.hasPermission("discordtowny.admin")).thenReturn(true);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());

        CommandContext<CommandSourceStack> ctx = createContext(admin);
        root.getChild("admin").getChild("update").getChild("status").getCommand().run(ctx);

        verify(admin).sendMessage(messages.get("updates.status-current", Map.of("current", "1.0.0")));
        verify(admin).sendMessage(messages.get("updates.check-failed", Map.of("reason", "could not reach GitHub")));
        verify(admin, never()).sendMessage(messages.get("updates.up-to-date"));
    }
```

---

## 5. Round 2 — Resolutions & Architecture Decisions

### 5.1 Case 1: `checksumMismatchAfterDeliveryDiscardsDownloadWithoutRemnants`

#### Decision: Origin vs. Delivery Host Provenance
The architect requested a decision between two interpretations:
1. Accept direct CDN delivery host URLs (`release-assets.githubusercontent.com`) as initial origins.
2. Require that initial downloads originate at the official repository URL and reach the delivery host via redirect hops, correcting the test fixture to reflect real API metadata.

**Resolution: Option 2 was selected — downloads must strictly originate at the official repository URL.**

**Rationale**:
- **Namespace and Repository Isolation**: The official origin URI (`https://github.com/Dasannn/DiscordxTowny/releases/download/...`) explicitly validates that the request belongs to repository `Dasannn/DiscordxTowny`. CDN delivery host URLs (`https://release-assets.githubusercontent.com/github-production-release-asset/<numeric-id>/...`) contain only numeric asset identifiers and ephemeral pre-signed storage tokens, without repository or owner paths. If the updater allowed direct requests to delivery hosts as initial download origins, an attacker could supply an asset URL pointing to an arbitrary asset hosted on GitHub's CDN from an untrusted third-party repository.
- **Contract and Test Consistency**: In Round 1, `updateSourcePolicyEnforcesExactDeliveryHostsOnRedirectOnly()` established the security invariant:
  ```java
  // Direct initial requests to delivery hosts without provenance are strictly rejected
  assertFalse(UpdateSourcePolicy.isAllowedInitialUri(liveRedirectTarget));
  assertThrows(IOException.class, () -> UpdateSourcePolicy.validateInitialUri(liveRedirectTarget));
  ```
  Weakening `isAllowedDownloadDestination` to accept direct delivery host URLs would break this security boundary and fail existing policy tests.
- **GitHub Releases API Behavior**: The official GitHub Releases API (`GET /repos/Dasannn/DiscordxTowny/releases/latest`) *always* returns `browser_download_url` targeting the official repository namespace on `github.com` (e.g. `https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar`). Clients navigating that URL receive an HTTP 302 redirect to the pre-signed storage asset on `release-assets.githubusercontent.com` or `objects.githubusercontent.com`.
- **Fixture Fix**: The test fixture in `checksumMismatchAfterDeliveryDiscardsDownloadWithoutRemnants` mistakenly assigned the direct delivery redirect target URL to `release.downloadUrl()`, rather than the official repository URL where a real check starts. The fixture was updated to:
  ```java
  UpdateService.Release release = new UpdateService.Release(
          "1.0.0",
          "https://github.com/Dasannn/DiscordxTowny/releases/download/v1.0.0/DiscordTowny-1.0.0.jar",
          legitimateSha,
          "Release notes"
  );
  ```
- **Preserved Invariants**: All assertions remain completely intact. The download connects, receives the tampered bytes, calculates the SHA-256 digest, rejects the mismatch with `DownloadResult.CHECKSUM_MISMATCH`, and discards all temporary download files leaving zero remnants in `update/` with the active jar untouched.

---

### 5.2 Case 2: `failedCheckMarksStateAndRendersCheckFailedNeverUpToDate`

#### Issue
In `failedCheckMarksStateAndRendersCheckFailedNeverUpToDate`, the check failed due to a transport error (`IOException("Untrusted destination rejected by official source policy: ...")`). The test asserted:
```java
assertTrue(service.getLastCheckError().get().contains("could not reach GitHub"));
```
However, `lastCheckError` had captured raw exception text without the standard `"could not reach GitHub"` prefix. Additionally, timeouts, rate limits, and non-200 HTTP responses required consistent error state propagation across all exit paths in `DefaultUpdateService.doCheckForUpdate()`.

#### Resolution
1. **Consistent Check Failure State Recording**:
   - `lastCheckFailed.set(true)`
   - `lastCheckError.set(...)` with the required prefix `"could not reach GitHub: ..."` or `"could not reach GitHub (...) "`
   - `hasCheckedAtLeastOnce.set(true)`
2. **Unified Error Formatting Across All Paths**:
   - **`IOException` catch block**: Formats as `"could not reach GitHub (" + e.getMessage() + ")"`.
   - **`InterruptedException` catch block**: Sets `"could not reach GitHub (Update check interrupted)"`.
   - **Generic `Exception` catch block**: Formats as `"could not reach GitHub (" + e.getMessage() + ")"`.
   - **Pre-emptive Rate Limit**: Formats as `"could not reach GitHub: rate limit exceeded; resets at " + resetTime`.
   - **HTTP 403 / 429**: Formats as `"could not reach GitHub: rate limit exceeded (HTTP " + status + ")"`.
   - **Unexpected HTTP status (!= 200, != 304)**: Formats as `"could not reach GitHub: unexpected status " + status`.
   - **Request/Body Read Timeout**: Formats as `"could not reach GitHub (request timed out)"` or `"could not reach GitHub (reading body timed out)"`.
   - **Missing Asset/Checksum Resolution**: Formats as `"could not reach GitHub: could not resolve release jar or published checksum"`.
3. **Tri-State Status and Rendering**:
   - `service.checkStatus()` returns `CheckStatus.CHECK_FAILED` whenever `lastCheckFailed.get()` is `true`.
   - `service.renderStatusMessages()` checks `isLastCheckFailed()` first and renders `updates.check-failed` with the formatted reason.
   - It **never** emits `updates.up-to-date` when `lastCheckFailed` is `true`.
   - Network failure log deduplication (`networkErrorLogged`) silences duplicate console warnings after the first failure until successful recovery.

