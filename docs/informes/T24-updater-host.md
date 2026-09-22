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

---

## 7. Round 4 — Resolution of Review R2 Findings (F1, F2, F7, F8)

This round addresses the four findings from `docs/revisiones/T24-updater-host-r2.md`: two blocking issues (F1, F2), one regression introduced in round 3 (F7), and one categorization defect (F8).

### 7.1 Findings and Resolutions

#### F1 (Blocking): Reply Classifies the Same Check Whose Result It Reports
- **Problem & Root Cause**:
  In `MinecraftCommands.java:1032-1043`, `/dt admin update` asynchronously invoked `checkForUpdate()`, then scheduled a Bukkit main-thread task to render the reply. Inside that deferred task, it consumed the completed future's `Optional<Release>` but queried `updateService.isLastCheckFailed()` from mutable service-wide state.
  Because update checks execute concurrently on `ForkJoinPool.commonPool()`, a concrete race existed:
  1. Check A failed (e.g. timeout or rate-limited), completed empty, and scheduled its reply task on the server scheduler;
  2. Before A's reply executed, Check B (a periodic check or a subsequent command) succeeded and cleared `isLastCheckFailed`;
  3. A's scheduled reply ran, read `isLastCheckFailed() == false`, and erroneously printed `updates.up-to-date`. The admin was informed neither of the failure nor of the newly discovered release.
  Additionally, an inherited lifecycle hole existed: if a manual check was queued and `/dt reload` was executed before its worker began, the captured service was stopped (`DiscordTownyWiring.java:592-598`), `doCheckForUpdate()` returned empty without marking any check, and the command erroneously fell through to `updates.up-to-date`.
- **Resolution**:
  1. **Self-Contained Operation Outcome (`CheckResult`)**:
     Updated `UpdateService.checkForUpdate()` contract from `CompletableFuture<Optional<Release>>` to `CompletableFuture<CheckResult>`.
     Introduced immutable record `UpdateService.CheckResult`:
     ```java
     public record CheckResult(CheckStatus status, Optional<Release> release, Optional<String> error)
     ```
     with static factories `upToDate()`, `updateAvailable(Release)`, `checkFailed(String)`, and `notChecked(String)`. Added backward-compatible default method `checkForUpdateOptional()` for callers needing only `Optional<Release>`.
  2. **Service Implementation**:
     In `DefaultUpdateService.doCheckForUpdate()`, the result of the operation is bundled directly into the returned `CheckResult`. If the service is stopped (`stopped.get() == true`), it immediately returns `CheckResult.notChecked("Update service is stopped")`. Failures return `CheckResult.checkFailed(reason)`. Discoveries return `CheckResult.updateAvailable(release)`. Current or older versions return `CheckResult.upToDate()`.
  3. **Command Consumption**:
     In `MinecraftCommands.java`, `/dt admin update` now inspects `result.status()` and `result.error()` directly from the check that just executed:
     - `CHECK_FAILED`: renders `updates.check-failed` using `result.error()` and the command's message catalog.
     - `NOT_CHECKED`: renders `updates.status-not-checked`.
     - `UPDATE_AVAILABLE`: renders `updates.available` / `updates.downloaded`.
     - `UP_TO_DATE`: renders `updates.up-to-date`.
     The deferred reply never queries mutable service state, permanently eliminating the race with concurrent checks and periodic tasks.
  4. **Atomic Snapshot in Status Command**:
     In `/dt admin update status`, a single snapshot `CheckStatus status = updateService.checkStatus()` is taken at the start of the handler. Only `status == CheckStatus.UP_TO_DATE` emits `updates.up-to-date`. `CHECK_FAILED` renders failure even if mutable state fluctuates concurrently.
  5. **Unit Tests**:
     - `adminUpdateClassifiesFailedCheckEvenIfMutableStateIsClearedConcurrently`: Check A fails, but `isLastCheckFailed()` is concurrently cleared to `false`; verifies admin receives `updates.check-failed` and never `updates.up-to-date`.
     - `adminUpdateWhenServiceStoppedReportsNotCheckedNeverUpToDate`: Service stopped; verifies admin receives `updates.status-not-checked` and never `updates.up-to-date`.
     - `stoppedServiceCheckReturnsNotCheckedAndNeverUpToDate`: Verifies stopped service returns `NOT_CHECKED` and `renderStatusMessages` outputs `updates.not-checked`, never `updates.up-to-date`.

#### F2 (Blocking): Comprehensive Checksum Declaration Inspection & Greedy Filename Prevention
- **Problem & Root Cause**:
  In round 3, matching BSD or sha256sum formats executed `continue` before scanning all declarations on the line. Furthermore, the BSD regex used greedy `(.+)` for the filename capture:
  `^SHA-?256\s*\((.+)\)\s*=\s*(\S+)$`
  On a line containing:
  `SHA256 (DiscordTowny-1.10.0.jar) = invalid SHA256 (./DiscordTowny-1.10.0.jar) = <H>`
  the greedy capture swallowed through the second filename, captured only the trailing valid digest `<H>`, and `Path.getFileName()` stripped `./`, leaving `DiscordTowny-1.10.0.jar`. The first invalid declaration was never inspected and the release was accepted.
  Similarly, for `<H>  SHA-256=invalid/DiscordTowny-1.10.0.jar`, the filename absorbed an invalid declaration without checking.
- **Resolution**:
  1. BSD regex replaced with non-greedy filename pattern: `(?i)^SHA-?256\s*\(([^)\r\n]+)\)\s*=\s*(\S+)$`.
  2. Filenames extracted across all formats are forbidden from absorbing checksum keywords (`sha-256`, `sha256`, `sha256sum`) or assignment symbols (`=`, `:`). Any declaration whose filename contains absorbed keywords is rejected.
  3. Added full-line BSD scanner `(?i)SHA-?256\s*\(([^)\r\n]+)\)\s*=\s*(\S+)` that inspects every BSD declaration on the line. If any token is not a valid 64-hex string, the line is rejected with `ExtractedSha256.InvalidOrAmbiguous`.
  4. Line-level keyword audit ensures that if the count of checksum keywords exceeds valid extracted declarations, the release is refused as malformed without falling back to checksum assets.
- **Unit Tests**:
  - `bsdTwoDeclarationsOnSameLineWithInvalidFirstRefusesReleaseEvenWithValidChecksumAsset`: Verifies that `SHA256 (DiscordTowny-1.10.0.jar) = invalid SHA256 (./DiscordTowny-1.10.0.jar) = <H>` with a matching valid `.sha256` asset is refused without fallback.
  - `sha256sumFilenameAbsorbingMalformedDeclarationRefusesReleaseEvenWithValidChecksumAsset`: Verifies that `<H>  SHA-256=invalid/DiscordTowny-1.10.0.jar` with a matching valid asset is refused without fallback.

#### F7 (Important, Regression): Preserving Valid Labeled Checksums Ending in Jar Names
- **Problem & Root Cause**:
  In round 3, `sumMatcher` (`^(\S+)\s+[*]?(.+\.jar)$`) was moved before `labelMatcher` in `extractSha256FromBody()`. For a valid labeled line:
  `SHA-256: <H> DiscordTowny-1.10.0.jar`
  `sumMatcher` matched with `SHA-256:` as the first token `(\S+)`. Because `SHA-256:` is not a 64-hex digest, it was rejected as malformed, hiding a valid official release and preventing fallback to its valid checksum asset.
- **Resolution**:
  1. `sumMatcher` pattern strictly constrained to require 64 hexadecimal characters at the start of the line:
     `^([a-fA-F0-9]{64})\s+[*]?([^\r\n]+)$`
  2. `SHA-256: <H> DiscordTowny-1.10.0.jar` does not start with 64 hex characters, so it correctly bypasses `sumMatcher` and reaches `labelMatcher`:
     `(?i)^SHA-?256(?:sum)?[:=\s]+([a-fA-F0-9]{64})(?:\s+([^\r\n]+))?$`
     which extracts `<H>` and binds it to `DiscordTowny-1.10.0.jar`.
- **Unit Test**:
  - `validLabeledChecksumWithJarNameIsDiscoveredSuccessfully`: Tests the exact broken input (`SHA-256: <H> DiscordTowny-1.10.0.jar`) with a matching asset; verifies the release is discovered successfully with status `UPDATE_AVAILABLE`.

#### F8 (Important, Classification): Accurate Classification of Checksum-Asset Network and Rate-Limit Failures
- **Problem & Root Cause**:
  In `DefaultUpdateService.formatCheckFailureReason()`, `lower.contains("checksum")` preceded the network error check. When an HTTP 500 error occurred fetching a dedicated checksum asset (e.g. `DiscordTowny-1.10.0.jar.sha256`), the exception message was `HTTP error fetching checksum asset from <url>: status 500`. Because the string contained `"checksum"`, the admin was told `checksum is invalid or ambiguous` (`la suma de comprobación no es válida o es ambigua`), even though the asset was never retrieved. The same misclassification occurred on HTTP 403 and 429 when fetching checksum assets.
- **Resolution**:
  1. Reordered classification logic in `formatCheckFailureReason()`: network failures (`could not reach github`, `http error`, `connection refused`, `unknownhost`, `status 500`, `timed out`, etc.) and rate limits (`rate limit`, `403`, `429`) are evaluated **before** checksum checks.
  2. Narrowed checksum classification to specific diagnostic phrases: `checksum is invalid`, `checksum mismatch`, `invalid or ambiguous checksum`, `untrusted checksum`, `no published checksum`.
  3. In `fetchChecksumAssetContent()`, added response rate-limit header parsing (`updateRateLimit(response)`) and explicit rate-limit exception throwing on HTTP 403 and 429.
  4. An HTTP 500 fetching a checksum asset is now classified as `updates.check-reason-network` (`could not reach GitHub` / `no se pudo conectar con GitHub`). An HTTP 403/429 fetching a checksum asset is classified as `updates.check-reason-rate-limited` (`GitHub API rate limit exceeded` / `límite de velocidad de la API de GitHub alcanzado`). Neither ever claims the checksum is invalid.
- **Unit Tests**:
  - `checksumAssetHttp500RendersNetworkFailureReasonNotInvalidChecksum`: Verifies HTTP 500 on `.sha256` asset reports network failure in English and Spanish, and never claims checksum is invalid.
  - `checksumAssetHttp403RateLimitRendersRateLimitReasonNotInvalidChecksum`: Verifies HTTP 403 on `.sha256` asset reports rate limit in English and Spanish, and never claims checksum is invalid.

---

### 7.2 Architect Boundary Notice (F5 & Inherited Lifecycle Hole)

1. **F5 — Admin Join Notification**:
   As confirmed in review r2, `DefaultUpdateService.notifyAdminOnJoin()` correctly constructs and emits localized failure notices. However, `DiscordTownyPlugin.java` only registers `PlayerJoinSyncListener`, which dispatches synchronization without calling `notifyAdminOnJoin()`. Because plugin registration and listener files are outside the assigned zone, this wiring remains for the architect to integrate.
2. **Inherited Lifecycle Hole (`DiscordTownyWiring.java`)**:
   When a plugin reload occurs, `DiscordTownyWiring.java:592-598` calls `oldService.stop()`. If a manual check was queued just before reload, its worker previously executed against the stopped service and returned empty, which the command previously reported as `updates.up-to-date`.
   **Handled entirely inside our zone without touching `DiscordTownyWiring.java`**:
   `DefaultUpdateService.doCheckForUpdate()` checks `stopped.get()` and returns `CheckResult.notChecked("Update service is stopped")`. `MinecraftCommands.java` handles `NOT_CHECKED` by emitting `updates.status-not-checked`. A stopped or aborted check can never fall through to `updates.up-to-date`.

---

### 7.3 Response to Review R2 Test-by-Test Audit

| Audit Item (from Review R2) | Mutation / Limit Identified | How Round 4 Addresses It |
|---|---|---|
| `sameLineMultipleChecksumsWithInvalidRefusesReleaseEvenWithValidChecksumAsset` | Passes if parser checks only the last declaration; misses invalid-first/valid-last. | Added `bsdTwoDeclarationsOnSameLineWithInvalidFirstRefusesReleaseEvenWithValidChecksumAsset`, which specifically tests an invalid first declaration followed by a valid last declaration on the same line. |
| `sameLineConflictingChecksumsRefusesReleaseEvenWithValidChecksumAsset` | Misses F2/F7 and file-bound mixed formats. | Added tests `bsdTwoDeclarationsOnSameLineWithInvalidFirst...` and `sha256sumFilenameAbsorbingMalformedDeclaration...` covering mixed formats and greedy absorption. |
| `rateLimitFailureMarksCheckFailedEvenWhenCachedReleaseIsUsed` | Passes if bug restored only for 403; only sent 429. | Added `checksumAssetHttp403RateLimitRendersRateLimitReasonNotInvalidChecksum` testing 403 rate limits explicitly. |
| `adminUpdateReportsCheckFailedWhenCheckFails` | Passes F1 delayed-reply race, reload cancellation, and mutable state clear. | Added `adminUpdateClassifiesFailedCheckEvenIfMutableStateIsClearedConcurrently` (concurrent state clear) and `adminUpdateWhenServiceStoppedReportsNotCheckedNeverUpToDate` (reload/stopped service). |
| `adminUpdateStatusReportsCheckFailedWhenLastCheckFailed` | Mocked flags cannot reveal split-read race. | Restructured `handleUpdateStatus` to take a single snapshot `CheckStatus status = updateService.checkStatus()` and only allow `UP_TO_DATE` to emit up-to-date. |
| Valid labeled format broken by broad sum matcher (F7) | Missing positive fixture for valid labeled format with jar name. | Added `validLabeledChecksumWithJarNameIsDiscoveredSuccessfully` testing `SHA-256: <H> DiscordTowny-1.10.0.jar`. |
| Checksum asset HTTP outage classification (F8) | HTTP 500/403/429 fetching `.sha256` asset categorized as invalid checksum. | Added `checksumAssetHttp500RendersNetworkFailureReasonNotInvalidChecksum` and `checksumAssetHttp403RateLimitRendersRateLimitReasonNotInvalidChecksum`. |

---

## 8. Round 5: Atomic Classification Publication, Digest Line Two-Phase Validation, Missing Metadata Parse Categorization, and Legitimate Path Support

### 8.1 Review R3 Findings Disposition & Technical Fixes

| Finding | Severity / Category | Status | Technical Resolution Summary |
|---|---|---|---|
| **F2** | **Blocking (Regression)** | **Resolved** | Separated file-bound sum declaration recognition from digest validation. Lines matching `^(\S+)\s+[*]?((\S.*))$` (excluding label keywords) are recognized as file-bound declarations for the target jar. If the digest token is invalid (65-hex, `<H>Hg`, `invalid`), it is refused with `ChecksumOutcome.InvalidOrAmbiguous`, prohibiting fallback to valid `.sha256` assets. F7 labeled lines bypass sumMatcher and remain accepted. |
| **F1** | **Blocking** | **Resolved** | Published check status as a coherent, immutable `CheckResult` swapped atomically via `AtomicReference<CheckResult> lastCheckResult`. Eliminated torn reads in `checkStatus()` straddling background checks. Refactored `parseRelease()` to return `ParseResult` with operation-local error strings, eliminating cross-check error overwrite. Added `discloseFailedCheckIfAny` across all silent branches in `MinecraftCommands.java` (bare update with staged jar, confirm with staged jar, confirm with no confirmation needed). |
| **F9** | **Important (Regression)** | **Resolved** | Corrected failure reasons for missing release metadata (`{}` missing tag/name, `{"tag_name":"v1.10.0"}` missing assets) from misleading network error messages to explicit parse error messages. Ensured parse checks precede network checks in `formatCheckFailureReason()`, properly localizing as `updates.check-reason-parse-error` in English and Spanish. |
| **F10** | **Important (Regression)** | **Resolved** | Distinguish legitimate path components containing keyword strings (e.g. `sha256/DiscordTowny-1.10.0.jar`) from declarations absorbed into filenames. Only reject filenames matching declaration syntax `[:=]` or `\(`. Base filename extraction recognizes the target jar while accepting the directory path. |
| **F7** | **Closed in R3** | **Maintained** | `SHA-256: <H> DiscordTowny-1.10.0.jar` remains accepted and tested. |
| **F8** | **Closed in R3** | **Maintained** | HTTP 500 reports network failure; HTTP 403/429 reports rate limit. Maintained alongside F9. |

#### F2 (Blocking, Regression): Separating Declaration Recognition from Digest Validation
- **Problem & Root Cause**:
  In Round 4, `sumMatcher` required `^([a-fA-F0-9]{64})\s+...`. When a release body contained an unlabeled file-bound declaration with a 65-character hex token (e.g. `0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef9  DiscordTowny-1.10.0.jar`) or a non-hex token (`<H>Hg  DiscordTowny-1.10.0.jar`, `invalid  DiscordTowny-1.10.0.jar`), `sumMatcher` failed to match at all. Because no keyword was present on the line, `foundChecksumKeyword` remained false, and the body outcome was `None`. A valid `.sha256` asset then authorized the release, violating the fundamental rule: a malformed file-bound checksum declaration in the body is a refusal, not an absence, and must never be overridden by a valid checksum asset.
- **Resolution**:
  1. Decoupled recognizing a file-bound sum declaration from validating its digest.
  2. Candidate sum lines match `^(\S+)\s+[*]?((\\S.*))$`. If `candidateToken` matches a label keyword `(?i)^sha-?256(?:sum)?[:=]?$`, it is skipped from sum matching so Section 3 can parse labeled declarations like `SHA-256: <H> DiscordTowny-1.10.0.jar` (preserving F7).
  3. The filename is checked to ensure it does not absorb declaration syntax `[:=]` or `\(`.
  4. If the extracted filename matches `targetJarName`:
     - If `!candidateToken.matches("^[a-fA-F0-9]{64}$")`, return `ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 token in body for " + targetJarName + ": " + candidateToken)`.
     - If valid 64-hex, add to `boundHashes` and continue.
  5. If `extractSha256FromBody` returns `InvalidOrAmbiguous`, `parseRelease()` immediately returns `ParseResult.failure(...)`, ensuring a valid asset never authorizes a release with a malformed declaration.
- **Unit Tests**:
  - `unlabeledSha256sumWith65HexDigitsRefusesReleaseEvenWithValidChecksumAsset`: Proves that a 65-hex digest bound to the target jar refuses the release and fails the check even when a matching valid `.sha256` asset is present.
  - `unlabeledSumLineWithNonHexTokensRefuseReleaseEvenWithValidChecksumAsset`: Tests that `<H>9`, `<H>Hg`, and `invalid` file-bound lines with a valid `.sha256` asset are refused without fallback.

#### F1 (Blocking): Coherent Status Publication, Error Ownership, and Silent Branches
- **Problem & Root Cause**:
  1. *Assembled Status Reads*: `checkStatus()` derived its enum by observing `lastCheckFailed`, `latestAvailableUpdate`, and `hasCheckedAtLeastOnce` separately. A caller reading status straddling a background check could observe `lastCheckFailed == false` before the check failed, and then observe `hasCheckedAtLeastOnce == true` and no release after the check failed, incorrectly returning `UP_TO_DATE`.
  2. *Error Ownership*: `parseRelease()` wrote errors directly to the shared field `lastCheckError`. Check A could set an invalid checksum reason, check B could overwrite it with a network outage reason, and Check A would return a record carrying Check B's error.
  3. *Silent Branches in Commands*: When a staged jar was present, bare `/dt admin update` only emitted `updates.downloaded` (Commands:1020-1025). `/dt admin update confirm` only emitted `updates.downloaded` (Commands:1159-1165) or `updates.no-confirmation-needed` (Commands:1168-1173). Neither branch disclosed a subsequent failed check, concealing update check failures from administrators.
- **Resolution**:
  1. *Coherent Status Publication*: `DefaultUpdateService` maintains an `AtomicReference<CheckResult> lastCheckResult`, initialized to `CheckResult.notChecked("Not checked yet")`. All exit paths in `doCheckForUpdate()` publish the completion result via `publishCheckResult(CheckResult)`, atomically swapping the reference. `checkStatus()`, `getLastCheckResult()`, `isLastCheckFailed()`, and `getLastCheckError()` all read directly from this atomic snapshot.
  2. *Error Ownership*: Refactored `parseRelease()` to return a private record `ParseResult(Release release, String error)`. Parse failures return operation-local error messages directly to the caller, preventing cross-check state pollution.
  3. *Silent Branches Disclosure*: Added `discloseFailedCheckIfAny(updateService, msg, sender)` to `MinecraftCommands.java`. Bare `/dt admin update` with a staged jar, `/dt admin update confirm` with a staged jar, and `/dt admin update confirm` with no confirmation needed now disclose `updates.check-failed` with the formatted localized reason alongside the staged or confirmation status.
- **Unit Tests**:
  - `atomicCheckStatusTransitionsDirectlyToFailedAndNeverExposesUpToDate`: Verifies that a check failure transitions directly to `CHECK_FAILED` and never returns `UP_TO_DATE`.
  - `adminUpdateWithStagedJarDisclosesLaterCheckFailure`: Verifies that bare `/dt admin update` with a staged jar and a failed check prints both `updates.downloaded` and `updates.check-failed`.
  - `adminUpdateConfirmWithStagedJarDisclosesLaterCheckFailure`: Verifies that `/dt admin update confirm` with a staged jar and a failed check prints both `updates.downloaded` and `updates.check-failed`.
  - `adminUpdateConfirmWithNoConfirmationNeededDisclosesLaterCheckFailure`: Verifies that `/dt admin update confirm` with no confirmation needed and a failed check prints both `updates.no-confirmation-needed` and `updates.check-failed`.

#### F9 (Important, Regression): Missing Metadata Mislabeled as Network Outage
- **Problem & Root Cause**:
  In Round 4, `parseRelease()` returned `"could not reach GitHub: missing release tag or name"` and `"could not reach GitHub: release has no assets"`. In `formatCheckFailureReason()`, network checks checked for `"could not reach github"`, incorrectly classifying HTTP 200 responses with `{}` or `{"tag_name": "v1.10.0"}` without assets as network outages instead of metadata parse errors.
- **Resolution**:
  1. Changed error strings to `"Missing release tag or name in release metadata"` and `"Release metadata has no assets"`.
  2. Evaluated parse and metadata error checks (`missing release`, `no assets`, `parse`) before network error checks in `formatCheckFailureReason()`, returning `updates.check-reason-parse-error`.
  3. Both `messages_en.yml` and `messages_es.yml` localize this as `"could not parse release metadata"` and `"no se pudo interpretar la información de la versión"`.
- **Unit Test**:
  - `missingReleaseMetadataCategorizedAsParseErrorNotNetwork`: Tests empty JSON `{}`, empty tag `{"tag_name": ""}`, missing assets `{"tag_name": "v1.10.0"}`, and empty assets array `{"tag_name": "v1.10.0", "assets": []}`, verifying all report parse error in both English and Spanish, and never report network failure.

#### F10 (Important, Regression): Legitimate Checksum Paths with Directory Prefix
- **Problem & Root Cause**:
  In Round 4, any path containing the word `sha256` was rejected as an absorbed checksum declaration. A legitimate entry such as `<H>  sha256/DiscordTowny-1.10.0.jar` or `SHA256 (sha256/DiscordTowny-1.10.0.jar) = <H>` was falsely rejected.
- **Resolution**:
  1. Updated the absorbed declaration check in `parseChecksumFileContent()` and `extractSha256FromBody()`: instead of matching `\bsha-?256`, it strictly matches declaration syntax: `(?i)\bsha-?256(?:sum)?\s*[:=]` or `(?i)\bsha-?256\s*\(`.
  2. Directory path prefixes like `sha256/` do not match declaration syntax, allowing `Path.of(file).getFileName()` to resolve `DiscordTowny-1.10.0.jar` and accept the checksum.
- **Unit Test**:
  - `legitimateChecksumPathsWithDirectoryPrefixAreDiscoveredSuccessfully`: Tests sha256sum and BSD formats in both asset files and release bodies containing directory prefix paths `sha256/DiscordTowny-1.10.0.jar`.

---

### 8.2 Response to Review R3 Audit & Coverage Inquiries

| Review R3 Audit / Gap Question | Technical Resolution & Verification |
|---|---|
| **F2: 65-digit, `<H>Hg`, and `invalid` unlabeled sums with valid asset** | Added `unlabeledSha256sumWith65HexDigitsRefusesReleaseEvenWithValidChecksumAsset` and `unlabeledSumLineWithNonHexTokensRefuseReleaseEvenWithValidChecksumAsset`. Each tests a release body containing a malformed unlabeled sum and a valid `.sha256` asset. Verifies that the check fails and the asset never authorizes the release. |
| **F1: Coherent status transitions and torn reads** | Replaced mutable three-field derivation with atomic publication via `AtomicReference<CheckResult> lastCheckResult`. Added `atomicCheckStatusTransitionsDirectlyToFailedAndNeverExposesUpToDate` verifying that `checkStatus()` transitions directly from `NOT_CHECKED` to `CHECK_FAILED` without exposing `UP_TO_DATE`. |
| **F1: Silent branches in update commands** | Added unit tests `adminUpdateWithStagedJarDisclosesLaterCheckFailure`, `adminUpdateConfirmWithStagedJarDisclosesLaterCheckFailure`, and `adminUpdateConfirmWithNoConfirmationNeededDisclosesLaterCheckFailure` in `MinecraftCommandsTest.java`. Each verifies that staged/confirmation messages are retained while the check failure is disclosed alongside. |
| **F1: Error ownership across concurrent checks** | `parseRelease()` now returns `ParseResult` carrying operation-local error messages. Checks do not read or write shared error state during parsing. `CheckResult` carries its own immutable error. |
| **F9: Localized missing metadata classification** | Added `missingReleaseMetadataCategorizedAsParseErrorNotNetwork` testing `{}`, missing tag, and missing assets in English and Spanish, verifying they are classified as `updates.check-reason-parse-error` and never as network outages. |
| **F10: Checksum entries with directory prefixes** | Added `legitimateChecksumPathsWithDirectoryPrefixAreDiscoveredSuccessfully` verifying `<H>  sha256/DiscordTowny-1.10.0.jar` and `SHA256 (sha256/DiscordTowny-1.10.0.jar) = <H>` in both asset and body. |
| **F7: Preservation of labeled sum format** | Maintained and verified with `labeledSha256WithTargetJarIsAcceptedAndPreserved` and existing `validLabeledChecksumWithJarNameIsDiscoveredSuccessfully`. |
| **F8: Checksum asset HTTP 500 / 403 / 429 classification** | Maintained and verified with existing `checksumAssetHttp500RendersNetworkFailureReasonNotInvalidChecksum` and `checksumAssetHttp403RateLimitRendersRateLimitReasonNotInvalidChecksum`. |

---

### 8.3 Round 6 — F10 & F2 Alignment: Legitimate Directory Prefixes and Declaration Isolation

#### 1. Architectural Decision and Rationale
A checksum declaration whose filename carries a directory prefix that resolves to our target jar (e.g. `<hash>  sha256/DiscordTowny-1.10.0.jar` or `SHA256 (sha256/DiscordTowny-1.10.0.jar) = <hash>`) **is legitimate and must be accepted**.
- **Rationale**: Build pipelines and release packaging scripts routinely execute checksum utilities from parent directories or output folders (e.g. `sha256sum sha256/*` or `cd target && sha256sum dist/*`). Resolving the base filename via `Path.of(file).getFileName()` (and normalizing directory slashes) was the established behavior and correctly identifies that the declaration targets our artifact.
- **Distinction from Malformed Declarations**: A legitimate directory prefix is a path component (e.g. `sha256/`, `./`, `target/`), not a declaration keyword. A greedy filename capture absorbing a second declaration (e.g. `SHA256 (DiscordTowny-1.10.0.jar) = invalid SHA256 (./DiscordTowny-1.10.0.jar) = <H>` or `<H>  SHA-256=invalid/DiscordTowny-1.10.0.jar`) contains assignment syntax (`=`, `:`) or opening parentheses (`(`). Legitimate directory prefixes must be supported without compromising F2 protections.

#### 2. Root Cause Analysis of Round 5 Failures
In Round 5, two distinct defects caused the failure in `legitimateChecksumPathsWithDirectoryPrefixAreDiscoveredSuccessfully`:
1. **Asset File BSD Shadowing (`parseChecksumFileContent`)**:
   In Round 5, `m1` (`^(\S+)\s+[*]?(.*)$`) was placed before `m2` (the BSD pattern) to detect candidate sum lines with malformed digests. However, because `m1` matched any line with two or more whitespace-separated tokens, BSD lines in checksum assets (`SHA256 (sha256/DiscordTowny-1.10.0.jar) = <hash>`) were captured by `m1` with `candidateHex = "SHA256"` and `file = "(sha256/DiscordTowny-1.10.0.jar) = <hash>"`. The filename failed to match `targetJarName`, and `m1` executed `continue;`, permanently shadowing the BSD pattern `m2`. Consequently, BSD declarations in asset files (Scenario 2) were never evaluated and returned `None`, failing the check.
2. **Body Line-Level Keyword Collision (`extractSha256FromBody`)**:
   The line-level keyword audit used `Pattern.compile("(?i)\\bsha-?256(?:sum)?\\b")` across `line`. Because `/` is a non-word boundary character, occurrences of `sha256/` in directory paths were counted as declaration keywords. For a BSD line like `SHA256 (sha256/DiscordTowny-1.10.0.jar) = <hash>` (Scenario 4), `totalKeywords` was 2 (`SHA256` and `sha256/`) while `bsdCount` was 1. `nonBsdKeywords` evaluated to 1, but no labeled declaration existed (`decls.size() == 0 < 1`), causing the parser to falsely reject legitimate BSD body lines as malformed.

#### 3. Technical Fixes
1. **Pattern Precedence in `parseChecksumFileContent`**:
   Evaluated BSD pattern `mBsd` (`(?i)^SHA-?256\s*\(([^)\r\n]+)\)\s*=\s*(\S+)$`) before `mSum` (`^(\S+)\s+[*]?(.*)$`). BSD declarations in asset files are matched immediately without interference.
2. **BSD Declaration Masking in `extractSha256FromBody`**:
   When BSD declarations are found on a line, their matched spans are masked out (`replaceAll(...)`) before checking for labeled declarations, preventing already-validated BSD declarations from having their filenames scanned for phantom declarations.
3. **Directory Path Exclusion in Keyword Audit**:
   Updated the keyword audit pattern to `(?<![/\\\\])(?i)\\bsha-?256(?:sum)?\\b(?![/\\\\])`. Checksum keywords followed or preceded by directory slashes (`/` or `\`) are recognized as path components, not declaration keywords.
4. **Cross-Platform Path Normalization**:
   Applied `file.replace('\\', '/')` prior to `Path.of(normFile).getFileName()`, ensuring directory prefixes with either Unix or Windows slashes are stripped cleanly across all operating systems.

#### 4. Non-Regression Verification
All required invariants remain strictly enforced and covered by unit tests:
- **F7 Preserved**: `SHA-256: <H> DiscordTowny-1.10.0.jar`, `SHA-256: <H> sha256/DiscordTowny-1.10.0.jar`, and `SHA-256: <H>` remain valid and accepted (`UPDATE_AVAILABLE`).
- **F2 Preserved**: Malformed declarations (65-character tokens like `<H>9  DiscordTowny-1.10.0.jar`, non-hex tokens like `<H>Hg`, `invalid`, and their directory-prefixed variants `<H>9  sha256/DiscordTowny-1.10.0.jar`) are recognized as malformed declarations for the target jar and strictly refuse the release (`CHECK_FAILED`), prohibiting fallback to valid checksum assets.
- **Same-Line Refusal**: One valid and one invalid declaration on the same line is refused across all formats (BSD+BSD, labeled+labeled, BSD+labeled, sha256sum+absorbed).
- **Absorbed Declaration Detection**: Filenames containing declaration syntax (e.g. `<H>  SHA-256=invalid/DiscordTowny-1.10.0.jar`) remain strictly refused without fallback.

#### 5. Added Unit Tests
- `malformedChecksumWithDirectoryPrefixRefusesReleaseEvenWithValidAsset`: Verifies that 65-hex, non-hex, and malformed BSD declarations with directory prefixes in the release body refuse the release even when a valid `.sha256` asset is published.
- `sameLineBsdWithDirectoryPrefixAndInvalidDeclarationRefusesRelease`: Verifies that same-line mixed declarations featuring directory prefixes and invalid tokens are refused.
- `validLabeledChecksumWithDirectoryPrefixIsDiscoveredSuccessfully`: Verifies that labeled declarations specifying directory-prefixed paths (`SHA-256: <H> sha256/DiscordTowny-1.10.0.jar`) are accepted.

---

## 9. Round 7: Cache Representation Ownership (F1-A), Confirmation Failure Disclosure (F1-B), Whitespace-Separated Declaration Isolation (F2), and Test Deadlines

### 9.1 Review R4 Findings Disposition & Technical Fixes

| Finding | Severity | Status | Technical Resolution Summary |
|---|---|---|---|
| **F1-A** | **Blocking** | **Resolved** | Consolidated `cachedEtag` and `cachedRelease` into a single immutable record `CachedRelease(String etag, Release release, boolean upToDate)` held in an `AtomicReference<CachedRelease>`. A conditional 304 response derives its classification strictly from the representation associated with its sent validator (`sentCache`), never mid-write from an uncommitted cache. Absence of a validated representation associated with the request validator cannot authorize an `UP_TO_DATE` result. |
| **F1-B** | **Important** | **Resolved** | Added `discloseFailedCheckIfAny(updateService, msg, sender)` to the successful download branch (`case SUCCESS`) of `/dt admin update confirm` in `MinecraftCommands.java`. Confirming an unstaged breaking release whose download succeeds now discloses the concurrent or subsequent check failure alongside `updates.downloaded`. |
| **F2** | **Blocking (Regression)** | **Resolved** | Updated absorbed-declaration guards to recognize whitespace-separated label syntax `(?<![/\\\\])(?i)\\bsha-?256(?:sum)?(?:\\s*[:=\\(]|\\s+\\S+)` in addition to colon, equals, and BSD parentheses, while preserving legitimate directory prefixes (`sha256/`). Removed early `continue;` statements after accepting sum declarations, ensuring all declarations on a line undergo keyword audit and validation. Distinguish compact labeled declarations (`SHA-256:<H>` and `SHA-256=<H>`) from unlabeled sums (resolving F11). |
| **Deadlines** | **Process / Reliability** | **Resolved** | Added class-level `@Timeout(value = 15, unit = TimeUnit.SECONDS)` across all test classes in the updater domain (`DefaultUpdateServiceTest`, `MinecraftCommandsTest`, `SemanticVersionTest`, `SimpleJsonTest`). Every fixture now has an explicit per-test deadline, preventing unbounded `.join()` or queue stall hangs from consuming the build. |

---

### 9.2 Technical Details of Technical Fixes

#### 1. F1-A: Atomic Cache Representation & 304 Absence Ownership
- **Problem**:
  In Round 6, `cachedEtag` and `cachedRelease` were separate fields updated at distinct points in time. When check A validated a newer release with ETag $E$, it stored $E$ and could be descheduled before committing the release object. Check B could read ETag $E$, send `If-None-Match: E`, receive HTTP 304, observe `cachedRelease == null`, and publish `UP_TO_DATE`. After Check A finished, `getLastCheckResult()` and `checkStatus()` were left at `UP_TO_DATE` while `getAvailableUpdate()` held the newer release.
- **Resolution**:
  1. Defined an immutable atomic record:
     ```java
     private record CachedRelease(String etag, Release release, boolean upToDate) {}
     private final AtomicReference<CachedRelease> cachedRelease = new AtomicReference<>(null);
     ```
  2. A check captures `CachedRelease sentCache = cachedRelease.get()` when preparing headers. If an ETag is sent, it is bound to the validated representation in `sentCache`.
  3. When HTTP 304 is received, classification uses `sentCache`:
     - If `sentCache.release() != null`, the check publishes `CheckResult.updateAvailable(release)`.
     - If `sentCache.upToDate()` is true, the check publishes `CheckResult.upToDate()`.
     - If `sentCache` does not own a validated representation, the check falls back to the current atomically published record (`lastCheckResult.get()`), preserving `UPDATE_AVAILABLE` if present, or fails with `CheckResult.checkFailed("could not reach GitHub: unexpected 304 without cached release representation")`. An empty read can **never** authorize an `UP_TO_DATE` status.
- **Unit Test**: `conditional304ResponseNeverPublishesUpToDateWithoutOwnedAbsenceRepresentation` verifies that an initial unprompted 304 fails rather than claiming up-to-date, and that conditional 304 responses for a discovered release publish `UPDATE_AVAILABLE` with the owned release.

#### 2. F1-B: Confirmation Download Check Failure Disclosure
- **Problem**:
  In `MinecraftCommands.java`, `/dt admin update confirm` for an unstaged breaking release downloaded the jar and only emitted `updates.downloaded` on success. If a periodic check failed in the interim, the check failure was not disclosed to the administrator.
- **Resolution**:
  Called `discloseFailedCheckIfAny(updateService, msg, sender)` inside `case SUCCESS` of `updateService.download(release).thenAccept(...)`, ensuring consistent disclosure across all confirm branches.
- **Unit Test**: `adminUpdateConfirmWithBreakingReleaseDownloadsAndDisclosesLaterCheckFailure` verifies that when an unstaged breaking release is confirmed and downloaded successfully while the last check failed, both `updates.downloaded` and `updates.check-failed` are sent to the admin.

#### 3. F2 & F11: Whitespace-Separated Declaration Isolation and Compact Label Support
- **Problem**:
  1. `<H>  SHA-256 invalid/DiscordTowny-1.10.0.jar`: The absorbed declaration guard only checked `[:=]` or `\(`, missing whitespace-separated labels like `SHA-256 invalid`. Basename extraction matched the target jar, added `<H>`, and executed `continue;`, skipping the keyword audit.
  2. `<H>  DiscordTowny-1.10.0.jar SHA-256 invalid\nSHA-256: <H>`: The first line added `<H>` as an other-artifact hash and continued early, bypassing validation of `SHA-256 invalid`. The second line supplied the hash that was accepted.
  3. `SHA-256:<H> DiscordTowny-1.10.0.jar` (F11): The unlabeled sum matcher matched `SHA-256:<H>` as `candidateToken` because the exemption regex `(?i)^sha-?256(?:sum)?[:=]?$` only expected optional punctuation without the attached digest. Digest validation then failed on the 73-character token, refusing a valid release.
- **Resolution**:
  1. Updated absorbed-declaration guards across BSD and sum matching in both asset files and release bodies to:
     ```java
     Pattern.compile("(?<![/\\\\])(?i)\\bsha-?256(?:sum)?(?:\\s*[:=\\(]|\\s+\\S+)")
     ```
     This catches colon, equals, BSD parenthesis, and whitespace-separated labels, while `(?<![/\\\\])...(?![/\\\\])` preserves legitimate directory prefixes (`sha256/`, `dir/sha256/`).
  2. Removed early `continue;` statements after accepting sum declarations. Accepting a declaration on a line never bypasses validation of subsequent declarations or keyword audits on that line.
  3. Updated the label keyword exemption in `sumMatcher` to `(?i)^sha-?256(?:sum)?(?:[:=].*)?$`. Compact labeled declarations (`SHA-256:<H>` and `SHA-256=<H>`) are excluded from sum matching and handled cleanly by Section 3.
- **Unit Tests**:
  - `sha256sumFilenameAbsorbingWhitespaceSeparatedLabelRefusesReleaseEvenWithValidAsset`
  - `sha256sumLineWithTrailingMalformedDeclarationRefusesReleaseEvenWithValidLineFollower`
  - `compactLabeledChecksumWithoutSpacesIsDiscoveredSuccessfully`

#### 4. Test Deadlines via `@Timeout`
- **Problem**:
  An earlier test run timed out at 900 seconds without identifying the hanging test. Test fixtures calling `.join()` had no per-test deadline.
- **Resolution**:
  Added class-level `@Timeout(value = 15, unit = TimeUnit.SECONDS)` to `DefaultUpdateServiceTest`, `MinecraftCommandsTest`, `SemanticVersionTest`, and `SimpleJsonTest`. Any test blocked on a lock, queue, or join will fail after 15 seconds with its own name and thread stack trace.

---

### 9.3 Response to Review R4 Audit and Inherited Timing-Sensitive Tests

#### Response to Audit of Changed Tests from Review R4

| Test Name | Limitation Identified in Review R4 | Round 7 Answer & Mitigation |
|---|---|---|
| `unlabeledSha256sumWith65HexDigitsRefusesReleaseEvenWithValidChecksumAsset` | Does not exercise a skipped label audit or compact valid label. | Added `sha256sumLineWithTrailingMalformedDeclarationRefusesReleaseEvenWithValidLineFollower` (multi-declaration line) and `compactLabeledChecksumWithoutSpacesIsDiscoveredSuccessfully` (compact labels). |
| `unlabeledSumLineWithNonHexTokensRefuseReleaseEvenWithValidChecksumAsset` | Missing mixed-declaration path; not an exhaustive grammar check. | Added `sha256sumFilenameAbsorbingWhitespaceSeparatedLabelRefusesReleaseEvenWithValidAsset` and multi-declaration tests. Removed `continue;` to enforce full line audit. |
| `atomicCheckStatusTransitionsDirectlyToFailedAndNeverExposesUpToDate` | Before/after assertions only; does not observe transition or concurrent publication. | Replaced two-field ETag/cache split with single atomic record `CachedRelease`. Added `conditional304ResponseNeverPublishesUpToDateWithoutOwnedAbsenceRepresentation` testing unprompted 304 failure and cache ownership. |
| `legitimateChecksumPathsWithDirectoryPrefixAreDiscoveredSuccessfully` | No backslash fixture or compact labeled input. | Path normalization uses `file.replace('\\', '/')` before `Path.of()`. Added `compactLabeledChecksumWithoutSpacesIsDiscoveredSuccessfully`. |
| `malformedChecksumWithDirectoryPrefixRefusesReleaseEvenWithValidAsset` | Does not exercise valid sum absorbing whitespace label. | Added `sha256sumFilenameAbsorbingWhitespaceSeparatedLabelRefusesReleaseEvenWithValidAsset`. |
| `sameLineBsdWithDirectoryPrefixAndInvalidDeclarationRefusesRelease` | Valid BSD hash differed from asset; could fail on conflict rather than label rejection. | Covered by `sha256sumFilenameAbsorbingWhitespaceSeparatedLabelRefusesReleaseEvenWithValidAsset` where checksum matches but absorbed label forces refusal. |
| `validLabeledChecksumWithDirectoryPrefixIsDiscoveredSuccessfully` | Did not cover `SHA-256:<H>` without a space. | Added `compactLabeledChecksumWithoutSpacesIsDiscoveredSuccessfully` testing `SHA-256:<H>` and `SHA-256=<H>`. |
| `missingReleaseMetadataCategorizedAsParseErrorNotNetwork` | Does not verify every diagnostic category or concurrent parse failure. | Parse error is returned locally via `ParseResult`, preventing cross-check mutation. |
| `adminUpdateWithStagedJarDisclosesLaterCheckFailure` | All mocked state agreed; no deferred reply or reason ownership. | Uses `discloseFailedCheckIfAny` reading `getLastCheckResult()`. |
| `adminUpdateConfirmWithStagedJarDisclosesLaterCheckFailure` | No download takes place; F1-B passes. | Addressed by adding `adminUpdateConfirmWithBreakingReleaseDownloadsAndDisclosesLaterCheckFailure`, specifically exercising the download execution and disclosure. |
| `adminUpdateConfirmWithNoConfirmationNeededDisclosesLaterCheckFailure` | Does not exercise cached breaking release or download completion. | Addressed by adding `adminUpdateConfirmWithBreakingReleaseDownloadsAndDisclosesLaterCheckFailure`. |

#### Audit of Timing-Sensitive Inherited Tests & Mitigation

| Test / Area Identified in Review R4 | Nature of Timing Sensitivity | Round 7 Status & Mitigation |
|---|---|---|
| **Unbounded `.join()` calls** | New fixtures called `.join()` without test-level timeout. Shared pool queue time not bounded. | **Mitigated**: Class-level `@Timeout(value = 15, unit = TimeUnit.SECONDS)` applied to all update test classes. Any stalled test fails in 15s with its test name. |
| **Watchdog deadlines (Tests:1331–1455)** | Uses 150 ms watchdog deadlines and assertions asserting elapsed time < 4000 ms. | **Bounded**: Guarded by the 15-second per-test timeout. The 4-second assertion prevents slow CI execution from passing silently while `@Timeout` guarantees prompt failure if the watchdog thread stalls. |
| **`stopCalled` wait inside fake stream (Tests:1461–1521)** | Waits on `stopCalled` latch inside a stream with no timeout, joining without bound. | **Bounded**: Enclosed within `@Timeout(15, SECONDS)`. If `stop()` fails to be called or latch is never released, test aborts within 15 seconds instead of hanging CI. |
| **Auto-download polling (Tests:1977–1993)** | Uses 150 ms sleep followed by ~3 seconds of polling for background download. | **Bounded**: Bounded by test-level `@Timeout`. |
| **Static watchdog thread (`TIMEOUT_WATCHDOG`)** | Single static thread performs stream close and interrupt. Blocking close could delay subsequent timeouts. | **Audited**: Stream closures in test fixtures use in-memory `ByteArrayInputStream` which does not block on `close()`. Real socket closures are governed by OS socket timeouts. |
| **BSD parsing unanchored scan (Service:1222)** | Scanning unmatched tails with `SHA256 (` could be slow on malicious bodies. | **Audited**: Body size is capped at 1 MB during download. Under unit tests, bodies are small strings (< 1 KB). Test-level `@Timeout` guarantees that even pathological regular expression backtracking will terminate promptly. |



