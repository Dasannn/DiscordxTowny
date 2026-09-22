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

---

## 6. Round 3 — Production Command Wiring and Review Resolutions

### 6.1 Findings and Resolutions

#### F1 (Blocking): Production Command Wiring in `MinecraftCommands.java`
- **Problem**: Production commands `/dt admin update` and `/dt admin update status` previously treated an empty `Optional<Release>` from `checkForUpdate()` as `updates.up-to-date`. A server unable to reach GitHub was incorrectly told it was on the latest version. Furthermore, `checkStatus()` and `renderStatusMessages()` were unconsumed by production commands.
- **Resolution**:
  - The zone was widened to include `/dt admin update` subcommands in `src/main/java/com/discordtowny/minecraft/MinecraftCommands.java` and tests in `src/test/java/com/discordtowny/minecraft/MinecraftCommandsTest.java`.
  - Bare `/dt admin update` now queries `updateService.isLastCheckFailed()` upon completion of `checkForUpdate()`. When failed, it emits `updates.check-failed` formatted with the localized reason category, never claiming the server is up-to-date. In addition, `exceptionally(...)` completions map directly to `updates.check-failed`.
  - `/dt admin update status` now explicitly distinguishes all four states:
    1. **Never checked**: Sends `updates.status-current` followed by `updates.not-checked` (via `checkStatus() == CheckStatus.NOT_CHECKED`), never `updates.up-to-date`.
    2. **Check failed**: Sends `updates.status-current` followed by `updates.check-failed` with the localized reason, never `updates.up-to-date`.
    3. **Checked and up to date**: Sends `updates.status-current` followed by `updates.up-to-date`.
    4. **Update available / downloaded**: Sends `updates.status-current` followed by `updates.available` or `updates.downloaded` (with breaking change warnings and notes summaries).
    5. **Cached update present with failed latest check**: Sends `updates.status-current`, then reports the cached update, AND reports the check failure via `updates.check-failed`.
  - Covered by unit tests in `MinecraftCommandsTest`:
    - `adminUpdateStatusReportsNotCheckedWhenNeverChecked`
    - `adminUpdateStatusReportsCheckFailedWhenLastCheckFailed`
    - `adminUpdateStatusReportsAvailableAndCheckFailedWhenCachedCheckFailed`
    - `adminUpdateReportsCheckFailedWhenCheckFails`
    - `adminUpdateReportsCheckFailedWhenCheckThrowsExceptionally`
    - `adminUpdateForConsoleReportsCheckFailedInEnglish`

#### F2 (Blocking): Same-Line Checksum Declaration Scanner
- **Problem**: `extractSha256FromBody()` used `labelMatcher.find()` which processed only the first match on a line. A body line containing `SHA-256: <valid 64-hex> SHA-256: invalid` or conflicting valid declarations on the same line accepted the first declaration and ignored the second.
- **Resolution**:
  - Rewrote line inspection in `DefaultUpdateService.java` to scan every `(?i)(?:sha-?256(?:sum)?[:=\s]+)(\S+)` declaration on the line.
  - Validates that every extracted token matches `^[a-fA-F0-9]{64}$`. Any invalid token anywhere on the line immediately causes the line to be rejected with `ExtractedSha256.InvalidOrAmbiguous`.
  - Checks that multiple declarations on the same line declare identical hex digests; conflicting declarations immediately return `ExtractedSha256.InvalidOrAmbiguous`.
  - Added unit test `sameLineMultipleChecksumsWithInvalidRefusesReleaseEvenWithValidChecksumAsset`: tests a release with `SHA-256: <valid 64-hex> SHA-256: invalid` on one line where the release provides an otherwise valid `.sha256` asset matching the valid hash. The release is refused and does not fall back to the asset.
  - Added unit test `sameLineConflictingChecksumsRefusesReleaseEvenWithValidChecksumAsset`: tests conflicting hashes on the same line with a valid asset; release is refused.

#### F3 (Important): Status Reporting and Cache State Separation
- **Problem**: When a release was cached from an earlier check, hitting an HTTP 403/429 rate limit or rate-limit shortcut returned the cached release before recording failure, leaving `isLastCheckFailed()` false and `checkStatus()` as `UPDATE_AVAILABLE`. `renderStatusMessages()` took available/staged branches first and omitted failure. A fresh service rendered `updates.up-to-date` although `checkStatus()` was `NOT_CHECKED`.
- **Resolution**:
  - In `DefaultUpdateService.doCheckForUpdate()`, rate limit responses (HTTP 403, 429, and window shortcuts) explicitly mark `lastCheckFailed.set(true)`, record `lastCheckError`, and mark `hasCheckedAtLeastOnce.set(true)` before returning the cached release.
  - Added `default boolean hasCheckedAtLeastOnce()` to `UpdateService`. On a fresh service where no check has completed, `checkStatus()` returns `CheckStatus.NOT_CHECKED`.
  - `renderStatusMessages()` now renders `updates.not-checked` on a fresh service. If a cached or staged update is present AND `isLastCheckFailed()` is true, it preserves and outputs the update information AND appends the `updates.check-failed` message with the localized reason.
  - Covered by unit tests `rateLimitFailureMarksCheckFailedEvenWhenCachedReleaseIsUsed` and `failedCheckMarksStateAndRendersCheckFailedNeverUpToDate`.

#### F4 (Important): Localized Failure Reasons Across Message Catalogs
- **Problem**: Only the outer sentence `updates.check-failed` was translated; its `{reason}` placeholder received raw English exception text (e.g. `could not reach GitHub (...)`, `Ambiguous jar assets in release`), causing Spanish messages to contain English leakage.
- **Resolution**:
  - Added message catalog keys to both `messages_en.yml` and `messages_es.yml`:
    - `updates.check-reason-network`
    - `updates.check-reason-rate-limited`
    - `updates.check-reason-no-jar`
    - `updates.check-reason-ambiguous-jar`
    - `updates.check-reason-no-checksum`
    - `updates.check-reason-invalid-checksum`
    - `updates.check-reason-timeout`
    - `updates.check-reason-interrupted`
    - `updates.check-reason-parse-error`
    - `updates.not-checked`
  - Implemented `DefaultUpdateService.formatCheckFailureReason(String rawError, Messages msg)` to map failure diagnostics to the localized message catalog keys.
  - Technical diagnostic details remain in English Java logs and `getLastCheckError()`.
  - In `failedCheckMarksStateAndRendersCheckFailedNeverUpToDate`, verified Spanish output renders `no se pudo conectar con GitHub` with zero untranslated English text.

#### F5 (Important): Administrator Join Notifications
- **Problem**: `notifyAdminOnJoin()` in `DefaultUpdateService` previously omitted notifications when a check failed with no cached/pending release, and was not invoked by `PlayerJoinSyncListener`.
- **Resolution**:
  - `notifyAdminOnJoin()` was updated to send `updates.check-failed` with the localized reason when `isLastCheckFailed()` is true and notifications are enabled.
  - *Zone boundary note*: Per task rules and permissions, `DiscordTownyPlugin.java` and `PlayerJoinSyncListener.java` are strictly outside the assigned zone (which is confined to the updater package, message catalogs, and `/dt admin update` subcommands in `MinecraftCommands.java`). The updater service's internal helper has been made fully correct.

#### F6 (Important): Stale Failure Reason Replacement
- **Problem**: `compareAndSet(null, ...)` in `doCheckForUpdate()` left an earlier network failure reason untouched if a later check completed with a parse error or missing metadata.
- **Resolution**:
  - Replaced `compareAndSet(null, ...)` with direct assignment for completed checks, ensuring each check attempt sets its specific diagnostic reason.
  - In `parseRelease()`, explicit error strings are recorded for JSON parse failures, missing tag/name, and missing assets.
  - Added unit test `consecutiveDifferentFailuresUpdatesLastCheckErrorToLatestReason`, verifying a network failure followed by malformed JSON updates `lastCheckError` to the parse error.

---

### 6.2 Test Audit Answers and Coverage Enhancements

| Review Audit Point | Action Taken |
| --- | --- |
| **Test 1 (`updateSourcePolicyEnforcesExactDeliveryHostsOnRedirectOnly`)**: Did not test `attacker.githubusercontent.com`, `raw.githubusercontent.com`, ports, case, trailing dot. | Added assertions verifying redirect rejection for `attacker.githubusercontent.com`, `raw.githubusercontent.com`, `release-assets.githubusercontent.com:8443`, and trailing dot `release-assets.githubusercontent.com.`. Verified acceptance of uppercase host normalization and explicit standard port 443. |
| **Test 2 (`jdkHttpTransportFollowsRedirectToDeliveryHosts`)**: Did not capture outgoing requests or test `objects.githubusercontent.com`. | Used `ArgumentCaptor<HttpRequest>` to prove the first send goes to the official origin and the second send goes to the delivery host CDN. Tested both `release-assets.githubusercontent.com` and `objects.githubusercontent.com`. |
| **Test 3 (`jdkHttpTransportLimitsRedirectChain`)**: Did not verify send count, modeled first response on CDN rather than origin, lacked boundary success test. | Modeled initial send to official origin redirecting to CDN hops. Verified exact send count of 6 sends before `Too many redirects (limit 5)` is thrown. Added a 5-hop boundary success test that reaches 200 OK and completes without error. |
| **Test 4 (`fullDownloadSucceedsThroughReleaseAssetsRedirectWithValidChecksum`)**: Injected lambda without 3xx/CDN hops; release body duplicate hash allowed skipping asset retrieval. | Rewired using real `JdkHttpTransport` and mock `HttpClient`. Placed no SHA in the release body to force asset retrieval. Verified metadata fetch (200), checksum redirect (302 -> 200 on CDN), and jar download redirect (302 -> 200 on CDN). Verified full download, digest verification, staging, and active-file preservation. |
| **Test 5 (`checksumMismatchAfterDeliveryDiscardsDownloadWithoutRemnants`)**: Lambda transport without redirect hops; did not verify transport call. | Rewired using real `JdkHttpTransport`. Verified 302 redirect from official jar download URL to delivery host CDN. Asserted transport calls occurred, verified tampered bytes produce `CHECKSUM_MISMATCH`, and proved update folder is clean with active jar untouched. |
| **Test 6 (`failedCheckMarksStateAndRendersCheckFailedNeverUpToDate`)**: Did not test `NOT_CHECKED` initial render; did not test failure after cached discovery; checked only fixed prefixes. | Tested initial `NOT_CHECKED` renders `updates.not-checked` and never up-to-date. Tested English and Spanish failure rendering with localized reason placeholder (`no se pudo conectar con GitHub`) and zero English leakage. Tested failure after cached discovery outputs both available update and check failure. |
| **Test 7 (`recoveryAfterFailedCheckClearsErrorState`)**: Did not verify exclusion of failure line on recovery; did not verify warning suppression resets; lacked 304 recovery test. | Verified recovered output contains up-to-date and contains no failure line. Verified that an outage following recovery logs a new warning (suppression reset). Verified recovery via HTTP 304 Not Modified clears failure state. |
| **Hostile redirect guard (`jdkHttpTransportRejectsUntrustedRedirect`)**: Did not stub response URI/body; failed before Location header check. | Stubbed `mockResponse.uri(officialUri)` and empty body, ensuring the policy exception triggers at Location header validation. Verified with `verify(mockClient)` that no request ever reached the untrusted host. Added tests for HTTPS downgrade and `raw.githubusercontent.com`. |
| **Ambiguity & Missing Checksum Fixtures**: Lacked multiple runnable jar fixture and discovery without checksum fixture. | Added unit tests `releaseWithMultipleRunnableJarsAmbiguityIsRefused` and `releaseDiscoveryWithoutPublishedChecksumIsRefused`. |
