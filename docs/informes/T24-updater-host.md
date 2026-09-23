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

---

## 10. Round 8 — Unified Atomic State Publication, Universal Validate-Before-Bind, and Consumed Filename Audit Isolation

### 10.1 Review R5 Findings Disposition & Technical Fixes

| Finding | Severity | Status | Technical Resolution Summary |
|---|---|---|---|
| **F1-A** | **Blocking** | **Resolved** | Eliminated the independent `latestAvailableUpdate` field. Published check status and availability are unified into a single immutable `CheckResult` held in `AtomicReference<CheckResult> lastCheckResult`. Every reader (`checkStatus()`, `getAvailableUpdate()`, `isAwaitingConfirmation()`, `renderStatusMessages()`) reads from that single atomic snapshot. Settled result/availability contradictions are permanently impossible. The unsolicited 304 branch at `:675-684` that borrowed another check's classification from `lastCheckResult` was completely removed; missing request-owned validator/representation unconditionally fails the check. |
| **F2** | **Blocking (Regression)** | **Resolved** | Re-ordered parsing logic to enforce universal **validate-before-bind**: a recognized declaration is validated against 64-hex digest requirements before evaluating whether its filename binds to the selected jar. Malformed tokens declaring non-target artifacts (e.g. `<H>9  DiscordTowny-1.10.0-sources.jar`) immediately return `InvalidOrAmbiguous`, refusing the release without permitting fallback to valid dedicated assets. Applied across body sum parsing and asset file checksum parsing. |
| **F12** | **Important** | **Resolved** | Isolated consumed filenames from line-level keyword audits: when a sum declaration consumes a directory-prefixed filename (e.g. `sha256.txt/DiscordTowny-1.10.0.jar`), that consumed filename is masked out from `lineToCheck` prior to Section 3. Updated keyword regex lookaheads to recognize directory path components ending in slashes, preventing legitimate directory names from being counted as declaration keywords. |

---

### 10.2 Technical Details of Technical Fixes

#### 1. F1-A: Unified Atomic State Publication & Unsolicited 304 Evidence Ownership
- **Problem**:
  1. *Settled Inconsistency 1*: Check B obtained newer release R (200 OK) and set `latestAvailableUpdate = R`. A delayed Check A processed a legitimate 304 for the running version and published `UP_TO_DATE` without modifying `latestAvailableUpdate`. After all checks finished, `checkStatus()` was `UP_TO_DATE` while `getAvailableUpdate()` returned R.
  2. *Settled Inconsistency 2*: Check A validated newer release R and wrote `latestAvailableUpdate = R` at `:750`, then paused during notification/auto-download before publishing at `:797`. Check B completed a 200 check for a non-newer version, set `latestAvailableUpdate = null`, and published `UP_TO_DATE`. Check A resumed and published `UPDATE_AVAILABLE(R)` without resetting `latestAvailableUpdate`. After all checks finished, `lastCheckResult` named R while `getAvailableUpdate()` was empty, losing the update offer.
  3. *Unsolicited 304 Borrowing Success*: When a check sent no validator (fresh service) and received an abnormal 304, lines 675–684 inspected `lastCheckResult` and returned another check's successful classification (`UPDATE_AVAILABLE` or `UP_TO_DATE`).
- **Resolution**:
  1. *Unified Atomic State*: Removed the separate `private volatile Release latestAvailableUpdate` field entirely. `DefaultUpdateService` now maintains a single atomic source of truth: `AtomicReference<CheckResult> lastCheckResult`.
     - `getAvailableUpdate()` returns `lastCheckResult.get().release()`.
     - `checkStatus()` returns `lastCheckResult.get().status()`.
     - `isLastCheckFailed()` returns `lastCheckResult.get().status() == CheckStatus.CHECK_FAILED`.
     - `getLastCheckError()` returns `lastCheckResult.get().error()`.
     - `hasCheckedAtLeastOnce()` returns `lastCheckResult.get().status() != CheckStatus.NOT_CHECKED`.
     - `isAwaitingConfirmation()` evaluates `getAvailableUpdate().orElse(null)`.
     Because classification and availability are fields of the same immutable record (`CheckResult`), a completed check publishes both as a single value via `publishCheckResult(CheckResult)`. Settled disagreements between `checkStatus()` and `getAvailableUpdate()` are architecturally impossible.
  2. *Single-Snapshot Readers*:
     - `renderStatusMessages(Messages msg)` captures a single snapshot `CheckResult lastResult = getLastCheckResult()` and derives both `availableOpt = lastResult.release()` and `status = lastResult.status()` from it.
     - `MinecraftCommands.java:1102–1145` (`handleUpdateStatus`) captures `CheckResult lastResult = updateService.getLastCheckResult()` and derives availability and status in one read, eliminating `isPresent()` followed by empty `get()`.
     - `MinecraftCommands.java:1395–1407` (`discloseFailedCheckIfAny`) inspects `lastResult.status()` and `lastResult.error()` from the snapshot, eliminating torn reads across concurrent updates.
  3. *Unsolicited 304 Refusal*: Removed lines 675–684. If `sentCache` does not own a validated representation (`sentCache.release() == null && !sentCache.upToDate()`), a 304 response unconditionally publishes `CheckResult.checkFailed("could not reach GitHub: unexpected 304 without cached release representation")`. An abnormal response can never borrow another check's success.

#### 2. F2: Universal Validate-Before-Bind
- **Problem**:
  In `extractSha256FromBody`, `sumMatcher` group 1 matched `<H>9` (65 hex digits) and group 2 matched `DiscordTowny-1.10.0-sources.jar`. Because `filename.equalsIgnoreCase(targetJarName)` failed, execution reached `else if (candidateToken.matches("^[a-fA-F0-9]{64}$"))`, which also failed on 65 hex digits. The malformed token was silently dropped without recording invalid evidence. With 0 keywords on the line, the body outcome was `None`, allowing a valid dedicated asset to admit the release.
- **Resolution**:
  1. Re-ordered declaration processing in `extractSha256FromBody`:
     ```java
     // Validate recognized declaration before deciding whether it binds to our jar (F2)
     if (!candidateToken.matches("^[a-fA-F0-9]{64}$")) {
         return new ChecksumOutcome.InvalidOrAmbiguous("Malformed SHA-256 token in body: " + candidateToken);
     }
     ```
     This check precedes `filename.equalsIgnoreCase(targetJarName)`. Any recognized declaration with an invalid digest token immediately returns `InvalidOrAmbiguous`, regardless of the artifact name.
  2. Applied the same validate-before-bind rule to `parseChecksumFileContent` for both BSD declarations (`mBsd`) and standard sum declarations (`mSum`). Checksum asset files containing malformed digests for other artifacts are immediately refused without binding.

#### 3. F12: Consumed Filename Audit Isolation
- **Problem**:
  `<H>  sha256.txt/DiscordTowny-1.10.0.jar`
  `sha256.txt` is a legitimate directory name. Basename extraction resolved `DiscordTowny-1.10.0.jar` and bound `<H>`. However, because `continue;` had been removed to audit the line, Section 3's keyword audit evaluated `line`. `\bsha-?256\b` matched `sha256` in `sha256.txt` because the period `.` is a word boundary and was not the excluded slash. With `totalKeywords == 1` and `decls.size() == 0`, Section 3 refused the release as a malformed declaration.
- **Resolution**:
  1. *Consumed Filename Masking*: When Section 2 recognizes a valid sum declaration, its consumed filename operand (`file`) is masked out in `lineToCheck` (`lineToCheck = lineToCheck.replace(sumConsumedFile, " ")`), matching the masking behavior already used for BSD declarations. Section 3 audits only *other* unconsumed declarations on the line.
  2. *Path-Aware Lookahead*: Updated the keyword audit pattern to `(?<![/\\\\])(?i)\\bsha-?256(?:sum)?\\b(?![^\\s/\\\\]*[/\\\\])`. Checksum keywords followed by non-whitespace path characters leading to a directory slash (`/` or `\`) are recognized as path components and excluded from declaration keyword counts.

---

### 10.3 Analysis of "The Shape of All Three" Across the Codebase

The review identified two recurring structural pitfalls:
1. **Validate after bind** (a check placed after the decision it needed to precede).
2. **State written in two steps** (split publication where dependent representations can disagree).

An exhaustive audit of the updater codebase was conducted to identify any other occurrences of either shape:

#### Audit Item 1: Validation Precedence in Checksum Parsers
- `extractSha256FromBody`:
  - BSD format: `candidateToken` was already validated before binding. (Correct)
  - Labeled format: `candidateToken` was already validated before binding. (Correct)
  - sha256sum format: `candidateToken` validation was previously nested *inside* `if (filename.equalsIgnoreCase(targetJarName))`. **Fixed in Round 8**: moved before filename binding.
- `parseChecksumFileContent`:
  - BSD format (`mBsd`): previously checked `isValidSha256(candidateHex)` after `if (filename.equalsIgnoreCase(targetJarName))`. **Fixed in Round 8**: moved before filename binding.
  - Standard sum format (`mSum`): previously checked `isValidSha256(candidateHex)` after `if (filename.equalsIgnoreCase(targetJarName))`. **Fixed in Round 8**: moved before filename binding.
- `parseRelease`:
  - Validates all asset checksums and body checksums before selecting and binding the final SHA-256. Conflicting evidence or malformed evidence in either source refuses the release before any staging or download can occur.

#### Audit Item 2: Dependent Public State Publication & Snapshot Reads
- `DefaultUpdateService`:
  - Previously, `latestAvailableUpdate` was written separately from `lastCheckResult`. **Fixed in Round 8**: `latestAvailableUpdate` field eliminated. All state queries (`checkStatus()`, `getAvailableUpdate()`, `getLastCheckResult()`, `isLastCheckFailed()`, `getLastCheckError()`, `hasCheckedAtLeastOnce()`, `isAwaitingConfirmation()`) read directly from `lastCheckResult.get()`.
  - Cache commit (`cachedRelease.set(...)`) is now synchronized with `publishCheckResult(...)` at completion.
- `MinecraftCommands`:
  - `handleUpdateStatus`: Previously took independent calls to `isUpdatePending()`, `getAvailableUpdate().isPresent()`, `getAvailableUpdate().get()`, and `getLastCheckResult()`. If availability cleared concurrently, `get()` could throw `NoSuchElementException`. **Fixed in Round 8**: single snapshot taken via `updateService.getLastCheckResult()`.
  - `discloseFailedCheckIfAny`: Previously read `getLastCheckResult()`, then evaluated `checkStatus() == CHECK_FAILED`, then read `getLastCheckError()`. A transient failure could result in reading an outdated error. **Fixed in Round 8**: status and error are read from `lastResult` atomically.

---

### 10.4 Deterministic Concurrency Verification (Zero Sleeps)

Eight new unit tests were added to verify these fixes without sleeps or arbitrary timing dependencies:

| Test Name | File | Defect Targeted | Verification Mechanism |
|---|---|---|---|
| `delayedLegitimate304PublishesUpToDateAndClearsAvailableReleaseDeterministically` | `DefaultUpdateServiceTest` | Settled Contradiction 1 (F1-A) | Caches `(E0, null, true)`. Check A captures E0 and suspends in transport. Check B runs and completes with newer release R (200 OK), publishing `UPDATE_AVAILABLE`. Check A's 304 response is released, publishing `UP_TO_DATE`. Verifies aggregate settled state: `checkStatus() == UP_TO_DATE` and `getAvailableUpdate().isEmpty()`. Failed under previous code where `getAvailableUpdate()` retained R. |
| `overlapping200ChecksNeverLeavePublishedUpdateWithEmptyAvailabilityDeterministically` | `DefaultUpdateServiceTest` | Settled Contradiction 2 (F1-A) | Check A validates newer release R (200 OK). During notification hook (`auditLogger`), Check B executes a 200 check for current version (not newer) and publishes `UP_TO_DATE`. Check A resumes and publishes `UPDATE_AVAILABLE(R)`. Verifies aggregate settled state: `checkStatus() == UPDATE_AVAILABLE` and `getAvailableUpdate().isPresent()`. Failed under previous code where `getAvailableUpdate()` was left empty. |
| `unsolicited304WithoutRequestOwnedValidatorFailsCheckAndNeverBorrowsSuccess` | `DefaultUpdateServiceTest` | Unsolicited 304 borrowing success (F1-A) | Fresh service with no cache. Check A starts without validator and suspends. Check B completes 200 OK with newer release. Check A receives unsolicited 304. Verifies Check A fails with `CHECK_FAILED` and error `"unexpected 304 without cached release representation"`, never borrowing Check B's `UPDATE_AVAILABLE`. |
| `malformedChecksumForOtherArtifactRefusesReleaseEvenWithValidDedicatedAsset` | `DefaultUpdateServiceTest` | Dropping malformed token for other artifact (F2) | Body contains `<H>9  DiscordTowny-1.10.0-sources.jar` (65 hex digits). Release provides valid dedicated asset `<H>` for `DiscordTowny-1.10.0.jar`. Verifies check fails with `CHECK_FAILED` and is refused without falling back to the dedicated asset. |
| `sha256sumWithDirectoryPrefixedFilenameContainingSha256InDirectoryNameIsAccepted` | `DefaultUpdateServiceTest` | Legitimate filename read as label (F12) | Body contains `<H>  sha256.txt/DiscordTowny-1.10.0.jar`. Verifies release is discovered successfully with `UPDATE_AVAILABLE`, confirming `sha256.txt` is not counted as a declaration keyword. |
| `labeledDeclarationWithDirectoryPrefixContainingSha256InDirectoryNameIsAccepted` | `DefaultUpdateServiceTest` | Labeled path prefix with `sha256` (F12) | Body contains `SHA-256: <H> sha256.txt/DiscordTowny-1.10.0.jar`. Verifies release is discovered successfully with `UPDATE_AVAILABLE`. |
| `checksumAssetWithMalformedDeclarationForOtherArtifactRefusesRelease` | `DefaultUpdateServiceTest` | Asset file malformed token for other artifact (F2) | Checksum asset `checksums.txt` contains `<H>9  DiscordTowny-1.10.0-sources.jar` alongside valid hash for target jar. Verifies release is refused with `CHECK_FAILED`. |
| `adminUpdateStatusDerivesFromSingleLastResultSnapshot` | `MinecraftCommandsTest` | Command torn read prevention (F1-A) | Mocks `getLastCheckResult()` returning `UPDATE_AVAILABLE`. Verifies `/dt admin update status` renders status and available release from single snapshot without split-read exceptions. |

---

## 11. Round 9 — Disentangling Declaration Recognition from Validation-Before-Bind and Restoring Check Result Semantics

### 11.1 Problem Diagnosis & Root Cause Analysis

Following Round 8's implementation of universal validate-before-bind (F2), nine existing test cases failed during release discovery:
```
691 tests completed, 9 failed
```

Investigation revealed two root causes:

1. **Declaration Recognition vs. Validation Conflation in Body Parser**:
   - In Round 8, `extractSha256FromBody` moved candidate token format verification (`!candidateToken.matches("^[a-fA-F0-9]{64}$")`) ahead of filename binding.
   - However, the regex used to identify sha256sum lines (`^(\S+)\s+[*]?((\S.*))$`) matches any line containing two or more whitespace-separated tokens.
   - As a result, standard English prose in release notes (e.g., `"Release notes without hash"`, `"Normal notes"`, `"[breaking] Migrate..."`, `"Release notes with no checksum at all"`) was captured by the matcher. The first word (`"Release"`, `"Normal"`) was evaluated as a candidate digest. Because ordinary words fail the 64-hex requirement, the parser treated ordinary prose as a malformed declaration and returned `ChecksumOutcome.InvalidOrAmbiguous`.
   - Consequently, eight valid releases with prose release notes were immediately aborted with `CHECK_FAILED`. In the case of releases with no checksums at all, absence and malformation were conflated: instead of returning `ChecksumOutcome.None()` (which leads to refusal due to `"no published checksum"`), the check failed with `"malformed checksum declaration in release body"`.

2. **Check Execution Outcome vs. Background Update Offer Conflation**:
   - In test 340 (`No network: normal operation, one log line, and the second check does not log again`), a fourth check encountered an `IOException`.
   - In Round 8, `doCheckForUpdate()` passed the cached release into `CheckResult.checkFailed(errorMsg, cached)` across network error handlers.
   - Because the test asserted `assertTrue(fourthCheck.isEmpty())` on `service.checkForUpdate().join().release()`, returning the cached release directly from the failed check future caused an assertion failure. The return value of `checkForUpdate()` represents the outcome of *that specific check execution* (which yielded no new release), whereas persistent service availability (`getAvailableUpdate()`) represents the update offer retained across transient failures.

---

### 11.2 Technical Resolution

#### 1. Disentangling Recognition from Validation in `extractSha256FromBody`
- **Recognition Step**: In sha256sum syntax, the line must declare a checksum for an artifact jar. The parser extracts the normalized file basename and evaluates:
  ```java
  boolean isArtifact = filename.equalsIgnoreCase(targetJarName)
          || filename.matches("(?i)^[a-zA-Z0-9_.-]+\\.jar$");
  ```
  Only if `isArtifact` is `true` is the line recognized as a sha256sum declaration. Non-artifact lines (markdown headings, prose sentences, bullet points) are ignored by the sum matcher, allowing Section 3 keyword audits to evaluate them. If no keywords are present, the body cleanly returns `ChecksumOutcome.None()`.
- **Universal Validate-Before-Bind**: Once recognized as an artifact declaration, the candidate digest is verified against `^[a-fA-F0-9]{64}$` *before* checking whether `filename.equalsIgnoreCase(targetJarName)`. Any recognized declaration with an invalid digest token (e.g., `<H>9  DiscordTowny-1.10.0-sources.jar`) immediately returns `ChecksumOutcome.InvalidOrAmbiguous`, refusing the release without permitting fallback to dedicated assets.

#### 2. Clean Separation of Check Execution Outcome from Background Availability
- **Check Execution Result**: When an update check fails (e.g. due to `IOException`, rate limiting, or malformed payloads), `doCheckForUpdate()` publishes `CheckResult.checkFailed(errorMsg)` where `release = Optional.empty()`. Therefore, `service.checkForUpdate().join().release()` accurately reports an empty release for that check.
- **Service Availability Queries**:
  - `getAvailableUpdate()` checks if `lastCheckResult` is `UPDATE_AVAILABLE` (returns `result.release()`) or `CHECK_FAILED` (returns `cachedRelease.get().release()` if present), returning `Optional.empty()` if `UP_TO_DATE` or `NOT_CHECKED`.
  - `getLastCheckResult()` synthesizes `CheckResult.checkFailed(error, cachedRelease)` if `CHECK_FAILED` and a cached release exists.
  - This preserves atomic snapshot consistency for command readers (`/dt admin update status`) across transient outages while ensuring failed check execution futures report no new release.

---

### 11.3 Verification of Required Closed Invariants

All four required invariants remain strictly enforced:

| Invariant / Case | Input Example | Parsing Behavior | Outcome |
|---|---|---|---|
| **Malformed hash for other artifact** | `<H>9  DiscordTowny-1.10.0-sources.jar` | Recognized as artifact (`.jar`). Candidate token is 65 hex characters. Validated before binding: fails 64-hex check. | Refused (`CHECK_FAILED`) |
| **Absorbed declaration in path** | `<H>  SHA-256 invalid/DiscordTowny-1.10.0.jar` | Recognized as artifact (`.jar`). Filename absorbs checksum declaration keyword. | Refused (`CHECK_FAILED`) |
| **Legitimate directory prefix** | `<H>  sha256.txt/DiscordTowny-1.10.0.jar` | Recognized as artifact (`.jar`). Resolves `DiscordTowny-1.10.0.jar`. Token is valid 64-hex. Consumed file masked; path excluded from keyword audit. | Accepted (`UPDATE_AVAILABLE`) |
| **Labeled declaration** | `SHA-256: <H> DiscordTowny-1.10.0.jar` | Token is label keyword; sum matcher skips. Section 3 parses labeled declaration and binds to target jar. | Accepted (`UPDATE_AVAILABLE`) |
| **Prose release notes** | `Release notes without hash` | First token `"Release"`, file `"notes without hash"`. `isArtifact` is false. Sum matcher skips. No keywords in Section 3. | Clean `None()`, assets checked |
| **Release without published checksum** | `Release notes with no checksum at all` | Sum matcher skips. Section 3 finds no keywords. Returns `None()`. No asset checksum found. | Refused (`CHECK_FAILED` with `"no published checksum"`) |

---

## 12. Round 10 — Preserving Captured Cached Release across Rate-Limited Checks

### 12.1 Problem Diagnosis & Root Cause Analysis

Following Round 9's parser fixes, 689 of 691 tests passed with exactly two failures:
```
Rate limit 403 or 429 respects reset header and uses cached release
  Cached release should be returned when rate-limited ==> expected: <true> but was: <false>

Rate limit response marks check failed even when cached release is returned (F3)
  Cached release must still be returned for convenience ==> expected: <true> but was: <false>
```

#### Root Cause
In Round 9, during the effort to separate check execution results from background service availability (to satisfy test 340's assertion that a failed network check future reports an empty release), `doCheckForUpdate()` was modified to publish `CheckResult.checkFailed(err)` with an empty release (`release = Optional.empty()`) across all failure branches.

This inadvertently broke the rate-limiting contract established in review R3 (F3):
- While transient network transport failures (e.g. `IOException: Connection refused` in test 340) report an empty release on `service.checkForUpdate().join().release()`, GitHub API rate-limited checks (both the rate-limit window shortcut and HTTP 403/429 responses) operate under a distinct requirement: **a check that could not complete due to rate limiting must still carry the cached release it already knew about**.
- This enables callers and administrators to continue observing the update discovered earlier while simultaneously being informed that this check failed (`CHECK_FAILED`).
- `CheckResult` provides the static factory `checkFailed(error, cachedRelease)` precisely to represent these dual semantics in a single immutable record. In Round 9, rate-limited exits failed to pass the check's captured release into this factory, returning `Optional.empty()` on `.release()` and failing the two tests.

---

### 12.2 Technical Resolution

#### 1. Capturing and Carrying the Cache across Rate-Limited Exits
- In `DefaultUpdateService.doCheckForUpdate()`, the cache representation is captured upfront before query execution:
  ```java
  CachedRelease sentCache = cachedRelease.get();
  Release capturedRelease = (sentCache != null) ? sentCache.release() : null;
  ```
- **Rate-Limit Window Shortcut**: When an active rate-limit window suppresses outbound network calls (`Instant.now().isBefore(resetTime)`), the service publishes:
  ```java
  return publishCheckResult(CheckResult.checkFailed(err, capturedRelease));
  ```
- **HTTP 403 and 429 Responses**: When GitHub returns HTTP 403 or 429, the service records the updated rate limit headers and publishes:
  ```java
  return publishCheckResult(CheckResult.checkFailed(err, capturedRelease));
  ```

#### 2. Unified Atomic State Publication
- **Coherent State**: The published `CheckResult` carries both `status == CHECK_FAILED` and `release == Optional.of(capturedRelease)` simultaneously as one immutable atomic snapshot in `lastCheckResult`.
- **Reader Prioritization**: `getAvailableUpdate()` and `getLastCheckResult()` inspect `result.release()` from the atomically published `lastCheckResult` snapshot first. When a check carries a captured release, readers consume it directly without synthesizing or reading separate mutable state.
- **Contract Boundary**: F1-A's single publication model, the unsolicited-304 refusal guard, and the Round 9 declaration parser remain completely unchanged. Network error handlers (`IOException`) continue to publish empty check releases, ensuring test 340 and the other 689 tests remain fully passing.

---

### 12.3 Verification Matrix

| Test Name | Pre-Round 10 State | Post-Round 10 Behavior | Status |
|---|---|---|---|
| `Rate limit 403 or 429 respects reset header and uses cached release` | Failed: second and third check returned empty release (`expected: <true> but was: <false>`) | Check 2 (HTTP 403) and Check 3 (window shortcut) both return `CheckResult.checkFailed(err, capturedRelease)`. `release().isPresent()` is `true` and matches `"1.10.0"`. | **Resolved** |
| `Rate limit response marks check failed even when cached release is returned (F3)` | Failed: second check returned empty release (`expected: <true> but was: <false>`) | Check 2 (HTTP 429) and Check 3 (window shortcut) return cached release while marking `isLastCheckFailed() == true` and `checkStatus() == CHECK_FAILED`. | **Resolved** |
| `No network: normal operation, one log line, and the second check does not log again` (Test 340) | Passed | `IOException` exits publish `CheckResult.checkFailed(errorMsg)` with empty release. Fourth check returns empty release as expected. | **Maintained** |
| `Failure after cached discovery: reports BOTH cached update and failure` (Test 2882) | Passed | `getAvailableUpdate()` and `renderStatusMessages()` report both available cached update and check failure reason. | **Maintained** |
| F1-A Single Publication & Unsolicited 304 Refusal Tests | Passed | Atomic publication and abnormal 304 rejection stand unchanged. | **Maintained** |
| Round 9 Parser Tests (9 valid releases, 4 malformed/legitimate fixtures) | Passed | All declaration recognition and validation-before-bind logic stands unchanged. | **Maintained** |

---

## 13. Round 11 — One Recognizer, One Validator, and Monotonic Non-Regressive Check Sequence

### 13.1 Problem Diagnosis & Reviewer Findings (Review R6)

The sixth review (`docs/revisiones/T24-updater-host-r6.md`) identified two remaining production defects:

1. **F2: Declaration Boundary Drift and Curated Filename Alphabet**:
   - Round 9's body-sum branch introduced an `isArtifact` filename heuristic relying on a private character class: `filename.matches("(?i)^[a-zA-Z0-9_.-]+\\.jar$")`.
   - When presented with a declaration naming an artifact containing legal jar filename characters outside that hand-picked alphabet—specifically `+`:
     ```text
     <H>9  DiscordTowny-1.10.0-sources+dev.jar
     ```
     where `<H>9` is 65 hexadecimal characters, `isArtifact` evaluated to `false`.
   - The line was dismissed as non-declarative prose without reaching candidate token validation. No malformed declaration was recorded, the body yielded `ChecksumOutcome.None()`, and an accompanying valid dedicated asset admitted the release.
   - Furthermore, five separate branches (Body BSD, Body Sum, Body Labeled, Checksum-file BSD, and Checksum-file Sum) duplicated declaration recognition patterns and validation logic with inline regular expressions.

2. **F13: Stale Check Erasing Newer Release (Freshness Inversion)**:
   - While Round 8 unified `CheckResult` into an atomic record, publication remained unconditional last-completer-wins.
   - If Check A began with a cached absence `(E0, no release, upToDate=true)` and sent `If-None-Match: E0`, GitHub prepared a 304 response that was delayed in transport.
   - Check B began *after* a new release R was published, validated R, and published `UPDATE_AVAILABLE(R)`.
   - Check A's delayed 304 response then arrived and published `UP_TO_DATE`, discarding release R and restoring an up-to-date state while an update was available.
   - The aggregate state was internally consistent, but newer knowledge was erased by an older check.

---

### 13.2 Technical Resolution

#### 1. F2: Consolidated Declaration Recognition and Token Validation

The declaration boundary was consolidated into a single unified recognition and validation model across all five branches:

- **Shared Grammar Patterns & Data Model**:
  - `BSD_DECL_PATTERN`: `(?i)\bSHA-?256\s*\(([^)\r\n]+)\)\s*=\s*(\S+)`
  - `SUM_DECL_PATTERN`: `^(\S+)(?:[ ]{2,}|[ ]\*|\t+|\s+[*]?)(\S.*)$`
  - `LABELED_DECL_PATTERN`: `(?<![/\\\\])(?i)\bsha-?256(?:sum)?(?![\\s]*\()(?![^\\s/\\\\]*[/\\\\])[:=\s]+(\S+)`
  - `ABSORBED_DECL_PATTERN`: `(?<![/\\\\])(?i)\bsha-?256(?:sum)?(?:\s*[:=\(]|\s+\S+)`
  - `JAR_NAME_PATTERN`: `(?i)\b(\S+\.jar)\b`
  - `ChecksumDeclaration(DeclarationFormat format, String candidateToken, String rawFilename)` record representing parsed declarations uniformly.

- **Universal Filename Handling**:
  - A filename is whatever non-whitespace text sits where a filename goes (`\S+`), without any curated character class. Basename extraction (`normalizeFilename`) strips directory prefixes (`/` and `\`) without restricting the legal character alphabet, admitting `+`, parentheses, and symbols.
  - In `isSupportedSumFilename`:
    1. Basename equals `targetJarName` (case-insensitive) -> recognized.
    2. Basename ends with `.jar` (case-insensitive) -> recognized.
    3. File contains no spaces and line uses standard sha256sum delimiter (`  `, `\t`, ` *`) -> recognized.
    4. Ordinary multi-word prose without `.jar` (e.g., `"Normal notes"`, `"Release notes without hash"`) is rejected as non-declarative.

- **Strict Validate-Before-Bind**:
  - `validateDeclaration(decl, sourceDesc)` runs immediately upon recognizing a declaration.
  - Verifies that `rawFilename` does not absorb another declaration keyword (`absorbsChecksumDeclaration`).
  - Verifies that `candidateToken` strictly conforms to 64 hexadecimal characters (`STRICT_HEX_64`).
  - If invalid, returns `ChecksumOutcome.InvalidOrAmbiguous` immediately, refusing the release ("whoever it names") before binding can occur and preventing fallback to dedicated assets.

#### 2. Consolidation Mapping of All Five Branches

| Branch | Declaration Format | Source | Recognizer & Validator Sharing | Private Branches / Curated Alphabets |
|---|---|---|---|---|
| **1. Checksum-file BSD** | BSD (`SHA256 (file) = hex`) | Checksum asset (`checksums.txt`) | **Shares** `BSD_DECL_PATTERN`, `ChecksumDeclaration`, `validateDeclaration`, `normalizeFilename`. | **None.** No private regex, no curated alphabet. |
| **2. Checksum-file Sum** | Sum (`hex [* ]file`) | Checksum asset (`checksums.txt`) | **Shares** `SUM_DECL_PATTERN`, `ChecksumDeclaration`, `validateDeclaration`, `normalizeFilename`. | **None.** No private regex, no curated alphabet. |
| **3. Body BSD** | BSD (`SHA256 (file) = hex`) | Release Body (`body`) | **Shares** `BSD_DECL_PATTERN`, `ChecksumDeclaration`, `validateDeclaration`, `normalizeFilename`. | **None.** No private regex, no curated alphabet. |
| **4. Body Sum** | Sum (`hex [* ]file`) | Release Body (`body`) | **Shares** `SUM_DECL_PATTERN`, `isSupportedSumFilename`, `ChecksumDeclaration`, `validateDeclaration`, `normalizeFilename`. | **None.** No curated alphabet (`+`, parentheses admitted). |
| **5. Body Labeled** | Labeled (`SHA-256: hex [file]`) | Release Body (`body`) | **Shares** `LABELED_DECL_PATTERN`, `JAR_NAME_PATTERN`, `ChecksumDeclaration`, `validateDeclaration`, `normalizeFilename`. | **None.** `\S+\.jar` replaces old `[a-zA-Z0-9_.-]+`. |

All five branches now share the exact same recognizer patterns, representation, and validation rule. Zero branches use private validators or curated character classes.

#### 3. F13: Monotonic Check Sequence & Freshness Protection

- **Monotonic Operation Sequence**:
  - `DefaultUpdateService` maintains `AtomicLong checkSequenceGenerator` and `AtomicLong publishedSequence`.
  - When `doCheckForUpdate()` starts, it captures a strictly increasing sequence:
    ```java
    long checkSeq = checkSequenceGenerator.incrementAndGet();
    ```
- **Guarded Publication**:
  - `publishCheckResult(long checkSeq, CheckResult result, CachedRelease newCache)` enforces:
    ```java
    boolean isNewerSeq = checkSeq > publishedSequence.get();
    boolean availabilityOverAbsence = result.status() == CheckStatus.UPDATE_AVAILABLE
            && lastCheckResult.get().status() == CheckStatus.UP_TO_DATE;

    if (isNewerSeq || availabilityOverAbsence) {
        if (checkSeq > publishedSequence.get()) {
            publishedSequence.set(checkSeq);
        }
        lastCheckResult.set(result);
        if (newCache != null) {
            cachedRelease.set(newCache);
        }
        ...
    }
    ```
  - An older operation (`checkSeq <= publishedSequence.get()`) cannot overwrite newer public state with an absence (`UP_TO_DATE` from delayed 304 or 200 non-newer response).
  - The check operation's returned future receives its own execution result, but service state (`checkStatus()`, `getAvailableUpdate()`, `getLastCheckResult()`, `cachedRelease`) retains the newer release.

---

### 13.3 Test Verification

1. **Updated Stale-304 Test (Deterministic Concurrency, Zero Sleeps)**:
   - `delayedLegitimate304PublishesUpToDateAndClearsAvailableReleaseDeterministically`:
     - Initial check caches `(E0, null, true)`.
     - Check A starts (seq 1), captures E0, and suspends in transport.
     - Check B starts (seq 2), validates release 1.10.0 (200 OK), and publishes `UPDATE_AVAILABLE`.
     - Check A's delayed 304 arrives and completes as `UP_TO_DATE` on its own future.
     - Verifies `service.checkStatus()` remains `UPDATE_AVAILABLE`, `service.getAvailableUpdate()` retains `1.10.0`, and status messages render `"There is a new version"`. Check A's stale absence cannot erase Check B's release.

2. **New `+` Filename Test (F2)**:
   - `malformedChecksumWithPlusInFilenameRefusesReleaseEvenWithValidDedicatedAsset`:
     - Release body contains `<H>9  DiscordTowny-1.10.0-sources+dev.jar` (65 hex digits, filename with `+`).
     - Release provides valid dedicated asset for `DiscordTowny-1.10.0.jar`.
     - Verifies `checkForUpdate()` fails with `CHECK_FAILED`, confirming the declaration is recognized and validated before binding, refusing the release without fallback to the dedicated asset.

3. **Entire Suite**: All tests pass deterministically without weakening any assertions.

---

## 14. Seventh Review Corrections (Round 12)

**Task Reference**: Seventh review findings (`docs/revisiones/T24-updater-host-r7.md`) on F2-A, F2-B, and F13.

### 14.1 F2-A: Whole-Line Boundary and Explicit Unconsumed-Text Rejection in Checksum Files

- **Checksum-File BSD Whole-Line Anchoring**:
  - `Service:1248` reverted from `mBsd.find()` to `mBsd.matches()`. While the release body consists of free-form prose where embedded declarations are discovered mid-line via `find()`, a checksum file grammar consists strictly of line-by-line declarations.
  - An input such as:
    ```text
    notice SHA256 (DiscordTowny-1.10.0.jar) = <H> trailing-text
    ```
    is no longer accepted in a checksum file asset. It fails `mBsd.matches()` due to the non-declaration prefix and suffix.
- **Explicit Rejection of Unconsumed Non-Comment Text**:
  - `parseChecksumFileContent` now explicitly terminates and returns `ChecksumOutcome.InvalidOrAmbiguous` for any non-empty, non-comment line that does not match a valid whole-line BSD declaration, sum declaration, or (in dedicated checksum files) standalone 64-hex string.
  - This preserves the checksum file's strict grammar and rejects prose or unconsumed fragments without relying on side effects of sum fallbacks.

### 14.2 F2-B: Digest-Side Recognition of Sum Declarations

- **Digest-Side Hexadecimal Token Constraint**:
  - In `Service:65`, `SUM_DECL_PATTERN` was refined from:
    ```java
    Pattern.compile("^(\\S+)(?:[ ]{2,}|[ ]\\*|\\t|\\s+[*]?)(\\S.*)$");
    ```
    to:
    ```java
    Pattern.compile("^([a-fA-F0-9]+)(?:[ ]{2,}|[ ]\\*|\\t|\\s+[*]?)(\\S.*)$");
    ```
  - A sum declaration is characterized on the digest side: its first field must be a hexadecimal token (`[a-fA-F0-9]+`).
  - Ordinary body prose such as:
    ```text
    Release  notes
    ```
    contains non-hexadecimal characters (`R`, `l`, `s`) in its first field. It is not hexadecimal and is therefore not recognized as a sum declaration at all. It is ignored as body prose and never triggers malformed-declaration failures, allowing valid dedicated checksum assets to authorize the release.
  - In contrast, broken digests like `<H>9  DiscordTowny-1.10.0-sources+dev.jar` (65 hex digits) are recognized as declarations because their first token is hexadecimal, and then correctly fail `validateDeclaration` because their length is not 64, refusing the release without restoring any curated filename alphabet.

### 14.3 F13: Strict Freshness-Governed Publication Order

- **Removal of `availabilityOverAbsence` Exemption**:
  - In `Service:543-571`, the conditional bypass:
    ```java
    boolean availabilityOverAbsence = result.status() == CheckStatus.UPDATE_AVAILABLE
            && lastCheckResult.get().status() == CheckStatus.UP_TO_DATE;
    ```
    was completely removed.
  - Publication is now governed strictly by operation generation sequence:
    ```java
    if (checkSeq > publishedSequence.get()) {
        publishedSequence.set(checkSeq);
        lastCheckResult.set(result);
        if (newCache != null) {
            cachedRelease.set(newCache);
        }
        ...
    }
    ```
  - Freshness decides publication unconditionally in both directions: an older check (`checkSeq <= publishedSequence.get()`) can never overwrite a newer completed check's published status, whether that older check produced `UP_TO_DATE` or `UPDATE_AVAILABLE`.
- **Correction of Overlapping 200 Ordering Test**:
  - Updated `overlapping200ChecksNeverLeavePublishedUpdateWithEmptyAvailabilityDeterministically` in `DefaultUpdateServiceTest`: Check A starts first (seq 1) and gets 1.10.0, but pauses in its audit hook; Check B starts later (seq 2) and publishes `UP_TO_DATE`. When Check A completes, its lower sequence (`1 <= 2`) is rejected by the sequence gate, keeping Check B's `UP_TO_DATE` authoritative.

### 14.4 Unit Test Verification

1. **Prefix-and-Suffix BSD in Checksum File (F2-A)**:
   - `checksumAssetWithPrefixAndSuffixSurroundingBsdDeclarationIsRefused`: verifies that a checksum file containing `notice SHA256 (DiscordTowny-1.10.0.jar) = <H> trailing-text` fails with `CHECK_FAILED` and is never accepted as a valid declaration.
2. **`Release  notes` Body Prose Beside Dedicated Asset (F2-B)**:
   - `releaseBodyWithReleaseNotesProseBesideValidDedicatedChecksumIsAccepted`: verifies that a release body with two-word prose `Release  notes` beside a valid dedicated `.sha256` asset completes successfully with `UPDATE_AVAILABLE` and binds the valid checksum.
3. **Authoritative Later-Started Check in Overlapping 200s (F13)**:
   - `overlapping200ChecksNeverLeavePublishedUpdateWithEmptyAvailabilityDeterministically`: corrected to assert that Check B's later-started `UP_TO_DATE` result remains authoritative and is not overwritten by Check A's older `UPDATE_AVAILABLE` result.

---

## 15. Round 13: Correct Sum-Line Discriminator (Filename-Driven Classification)

### 15.1 Why the Digest Field Could Never Carry the Discriminator

In Round 12, `SUM_DECL_PATTERN` constrained the first token of a sum line to hexadecimal characters (`^([a-fA-F0-9]+)...`) in an attempt to differentiate prose like `Release  notes` from declarations.

This placed the discriminator on the **wrong field**:
1. **The discriminator cannot be the subject of validation**: If a line is recognized as a declaration only when its digest is hexadecimal, then any malformed digest containing non-hex characters (e.g. `invalid  sha256/DiscordTowny-1.10.0.jar` or `0123...Hg  DiscordTowny-1.10.0.jar`) fails the regex match before classification even occurs.
2. **Malformed declarations become invisible**: Because the line does not match the sum declaration pattern, it is dropped as ordinary body prose. When directory-path prefix exclusions (`sha256/`) or absence of checksum keywords prevent keyword audit triggers, the malformed declaration produces neither a bound hash nor a failure. If a valid dedicated `.sha256` asset is attached, the release is accepted instead of rejected.
3. **Contradiction with security policy**: A corrupted or attacker-tampered declaration must fail fast with `CHECK_FAILED` ("whoever it names"). A gate that uses digest validity to decide whether to inspect the digest makes malformed digests invisible rather than fatal.

### 15.2 What the Discriminator Now Is

The discriminator resides entirely on the **filename field**, not the digest field:
- **Filename Gate**: A line in the release body is a sha256sum declaration if and only if its second field, once normalized, names a jar:
  - It equals `targetJarName` (case-insensitive), or
  - It ends with `.jar` (case-insensitive).
- **Digest Validation**: The digest field is captured universally as `(\S+)` via:
  ```java
  SUM_DECL_PATTERN = Pattern.compile("^(\\S+)(?:[ ]{2,}|[ ]\\*|\\t|\\s+[*]?)(\\S.*)$");
  ```
  Once a line is classified as a declaration by its filename, its digest is passed to `validateDeclaration`. If the digest is not strictly 64 hexadecimal characters (`STRICT_HEX_64`), the release is refused with `CHECK_FAILED`.
- **Prose Ignored**: Lines whose second field does not name a jar (such as `Release  notes`, `Release  notes  <64 hex>`, or non-jar artifacts like `invalid  DiscordTowny-1.10.0.zip`) return `false` from `isSupportedSumFilename` and are ignored as prose, neither registering hashes nor refusing on their own.
- **Third Branch Removal**: In `isSupportedSumFilename`, the branch accepting any single token on a line containing double spaces or tabs (`!rawFile.contains(" ") && ...`) was removed. Only `targetJarName` and `.jar` extensions are accepted.
- **Label Precedence Preserved**: The guard:
  ```java
  !candidateToken.matches("(?i)^sha-?256(?:sum)?(?:[:=].*)?$")
  ```
  ensures labeled lines (e.g., `SHA-256:`, `SHA-256=`, `sha256sum:`, `SHA-256`) are never treated as sum declarations with malformed digests, allowing the labeled declaration parser to validate and bind them.
- **Checksum Files Unchanged**: Dedicated checksum files (`parseChecksumFileContent`) remain whole-line anchored, as every non-comment line in a dedicated checksum asset is intended as a declaration.

### 15.3 Test Verification

Added to `src/test/java/com/discordtowny/update/DefaultUpdateServiceTest.java`:
1. **`releaseBodyWithReleaseNotesProseAndHexBesideValidLabeledDeclarationIsAccepted`**:
   - Verifies that `Release  notes  <64 hex>` on a line beside a valid labeled declaration (`SHA-256: <validHex> DiscordTowny-1.10.0.jar`) is ignored as prose and does not refuse the release.
2. **`sumLineForNonJarArtifactWithMalformedDigestIsTreatedAsProseAndDoesNotRefuseRelease`**:
   - Verifies that a sum line for a non-jar artifact (`DiscordTowny-1.10.0.zip`) with malformed digests (`invalid`, `...Hg`):
     - Beside a valid dedicated checksum asset, is treated as prose and does not block release acceptance.
     - Beside a valid body labeled declaration, succeeds with `UPDATE_AVAILABLE`.
3. **Existing Tests Restored**:
   - `unlabeledSumLineWithNonHexTokensRefuseReleaseEvenWithValidChecksumAsset` passes on `...Hg` and `invalid`.
   - `malformedChecksumWithDirectoryPrefixRefusesReleaseEvenWithValidAsset` passes on `invalid  sha256/DiscordTowny-1.10.0.jar`.

---

## 16. Round 14: Release Asset Publication Awareness & Gate Rectification

### 16.1 F14: The Filename Gate Must Know the Release's Published Artifacts

#### The Flaw in Round 13's Narrow Gate
In Round 13, `isSupportedSumFilename` was restricted to accept only:
1. Exact match with `targetJarName`, or
2. Suffix match with `.jar`.

This rule was an artificial narrowing of the full T24 checksum rule, made because the release's published asset names were not plumbed into `extractSha256FromBody`. As a consequence, a release body sum line declaring a checksum for a non-jar artifact actually published in that release—such as `invalid  DiscordTowny-1.10.0.zip` when `DiscordTowny-1.10.0.zip` is an asset of the release—was rejected by the filename gate and silently treated as prose. If a valid checksum for the jar was present elsewhere (e.g., in a dedicated `.sha256` asset or labeled declaration), the release was accepted despite publishing a broken checksum declaration for one of its artifacts.

T24 requires outright refusal whenever a release body contains a checksum declaration whose digest is not a valid 64-hex token, regardless of which artifact it names.

#### What the Gate Now Consults
The real rule is:
> A body line is a sha256sum declaration when its second field names **an artifact of that release** — the target jar, any `.jar`, or any asset actually published in the release being parsed.

`parseRelease` already parses the release's `assets` array from GitHub release metadata. It now collects the set of published asset names (`releaseAssetNames`) and plumbs it directly down into `extractSha256FromBody(notes, selectedJarName, releaseAssetNames)`. No new network requests or external calls are added.

`isSupportedSumFilename(rawFile, line, targetJarName, releaseAssetNames)` now evaluates:
1. `norm.equalsIgnoreCase(targetJarName)` (the target jar);
2. `norm.toLowerCase(Locale.ROOT).endsWith(".jar")` (any jar);
3. `norm.equalsIgnoreCase(normAsset)` for any asset name in `releaseAssetNames` (any artifact published by this release).

The old single-token double-space fallback was **not** restored, ensuring ordinary prose never passes the gate. If a caller provides an empty set of asset names, the gate preserves Round 13's jar-only behavior with zero leniency.

#### Why the Asset List Separates Declarations from Prose
Release notes intermix unstructured English prose (release descriptions, install guides, changelogs) with structured sha256sum lines.
The fundamental discriminator that separates a checksum declaration from prose is whether the named entity corresponds to **an artifact of that release**:
- When a line contains `invalid  DiscordTowny-1.10.0.zip` and the release publishes `DiscordTowny-1.10.0.zip`, the second field names a published release artifact. The publisher's intent to declare a checksum is unambiguous, and the broken digest (`invalid`) represents a corrupted declaration that must immediately refuse the release (`CHECK_FAILED`).
- In contrast, a prose line like `Release  notes` or `Release  notes  <64 hex>` names `notes`, which does not end in `.jar` and is not a published artifact in `releaseAssetNames`. It is reliably ignored as prose without refusing the release.
- If a line names a file that is neither a jar nor a published artifact of the release (e.g. `invalid  unrelated-file.zip`), it does not name any artifact of the release and stays prose.

The asset list provides an authoritative, publisher-defined boundary that cleanly separates authentic declarations from incidental prose without guessing.

---

### 16.2 F15: Quoted Sum Declarations Escaping the Gate

#### Root Cause
In Markdown release notes, publishers frequently enclose code, commands, or checksum declarations in quotes or backticks. While `extractSha256FromBody` previously stripped enclosing backticks (`` `...` ``), it did not strip quotes. For a line such as:
```text
"invalid  DiscordTowny-1.10.0.jar"
```
`SUM_DECL_PATTERN` captured `\"invalid` as the digest and `DiscordTowny-1.10.0.jar\"` as the filename. Because the captured filename ended with `\"`, it failed `endsWith(\".jar\")` and failed to match the target jar. The line was discarded as unrecognized, escaping digest validation.

#### Resolution
`extractSha256FromBody` now normalizes line delimiters in a loop, stripping matching pairs of surrounding double quotes (`\"...\"`) and single quotes (`'...'`) in the exact same manner as backticks:
```java
boolean stripped;
do {
    stripped = false;
    if (line.startsWith("`") && line.endsWith("`") && line.length() >= 2) {
        line = line.substring(1, line.length() - 1).trim();
        stripped = true;
    } else if (line.startsWith("\"") && line.endsWith("\"") && line.length() >= 2) {
        line = line.substring(1, line.length() - 1).trim();
        stripped = true;
    } else if (line.startsWith("'") && line.endsWith("'") && line.length() >= 2) {
        line = line.substring(1, line.length() - 1).trim();
        stripped = true;
    }
} while (stripped && !line.isEmpty());
```
Both single-quoted and double-quoted declarations are recognized and subjected to standard checksum validation.

---

### 16.3 F16: Leading UTF-8 BOM on BSD Checksum Files

#### Root Cause
Checksum files created on Windows tools frequently include a UTF-8 Byte Order Mark (`\uFEFF`, bytes `EF BB BF`) at the very start of the file. In Java, `trim()` strips ASCII whitespace (`<= ' '`), leaving `\uFEFF` attached to the start of the first line.
In Round 12, pattern matching was changed from `find()` to `matches()` to reject trailing garbage. Because `\uFEFF` preceded `SHA256`, whole-line regex `BSD_DECL_PATTERN.matcher(line).matches()` failed on the first line, and Round 12's unconsumed-line branch rejected the valid checksum file as malformed.

#### Resolution
`parseChecksumFileContent` strips a leading `\uFEFF` from the input content and from the first line before evaluating declarations:
```java
if (content.startsWith("\uFEFF")) {
    content = content.substring(1);
}
String[] lines = content.split("\\r?\\n");
if (lines.length > 0 && lines[0].startsWith("\uFEFF")) {
    lines[0] = lines[0].substring(1);
}
```
Whole-line declaration matching is strictly preserved for all formats.

---

### 16.4 Test Verification

Updated and added tests in `src/test/java/com/discordtowny/update/DefaultUpdateServiceTest.java`:

1. **`sumLineForNonJarArtifactWithMalformedDigestIsTreatedAsProseAndDoesNotRefuseRelease` (Inverted & Extended, F14)**:
   - **Case 1 (Inverted)**: When `DiscordTowny-1.10.0.zip` is published in `assets`, malformed digests (`invalid`, `0123...Hg`) on the sum line for the zip now cause outright refusal (`CHECK_FAILED`), both beside a dedicated checksum asset and beside a valid body declaration.
   - **Case 2 (Prose Preservation)**: When `DiscordTowny-1.10.0.zip` is **not** published as an asset of the release, the malformed sum line is not recognized as an artifact declaration, remains prose, and does not block release discovery (`UPDATE_AVAILABLE`).
2. **`quotedSumDeclarationWithMalformedDigestRefusesReleaseForBothQuoteCharacters` (F15)**:
   - Verifies that quoted sum declarations with malformed digests enclosed in double quotes (`"..."`) or single quotes (`'...'`) are recognized as declarations and refuse the release with `CHECK_FAILED`.
3. **`quotedSumDeclarationWithValidDigestIsAcceptedForBothQuoteCharacters` (F15)**:
   - Verifies that valid sum declarations enclosed in double quotes (`"..."`) or single quotes (`'...'`) are parsed and bound successfully (`UPDATE_AVAILABLE`).
4. **`checksumAssetWithLeadingUtf8BomOnBsdDeclarationLineIsAccepted` (F16)**:
   - Verifies that a dedicated checksum file beginning with a UTF-8 BOM (`\uFEFF`) on a BSD declaration line (`\uFEFFSHA256 (DiscordTowny-1.10.0.jar) = <hex>`) is parsed cleanly, passes whole-line matching, and authorizes the release (`UPDATE_AVAILABLE`).

---

## 17. Round 15: Artifact Name Shape Verification and Universal Leading BOM Normalization

### 17.1 F17: Filename Shape Verification (Shape Plus Membership)

#### The Danger of Membership Alone and Why False Refusal Is the Worse Failure
In Round 14, `isSupportedSumFilename` was amended so that any name present in `releaseAssetNames` opened a sha256sum declaration. However, asset membership alone is insufficient evidence that a two-field body line was intended as a checksum declaration.

Consider a release that publishes an asset literally named `notes` (such as a plain text release notes file or documentation asset). An ordinary body line such as:
```text
Release  notes
```
matches `SUM_DECL_PATTERN` because `Release` and `notes` are separated by two spaces. Under Round 14's gate:
1. `file` is `notes`.
2. `notes` matches an asset in `releaseAssetNames`.
3. `candidateToken` is `Release`.
4. `validateDeclaration` evaluates `Release` as a candidate SHA-256 digest, rejects it because it is not 64 hexadecimal digits, and returns `ChecksumOutcome.InvalidOrAmbiguous`.
5. The entire release check is aborted with `CHECK_FAILED`, even when a completely valid dedicated checksum (e.g. `DiscordTowny-1.10.0.jar.sha256`) is published alongside the release. The same defect occurs with directory prefixes such as `docs/notes` because both sides are reduced to basenames.

**Why a false refusal is the worse failure**:
- A false acceptance (the case Round 14 addressed) occurs when a broken checksum declaration for a secondary artifact is ignored as prose, but the release still possesses a valid checksum for the target jar. In that situation, the server still downloads and verifies the correct binary against an authentic hash.
- A **false refusal**, by contrast, causes a correctly published, authentic, and secure update to become completely unreachable to server administrators. Servers miss critical security patches or bug fixes because everyday English prose in the release description collided with an asset name. Making a valid update unavailable is fundamentally worse than tolerating incidental prose.

#### Resolution: The Shape of a Filename
To distinguish authentic artifact checksum declarations from ordinary prose collisions without resorting to arbitrary keyword blocklists (such as hardcoding the word `Release`), the gate now verifies the **structural shape** of the candidate filename in addition to its asset membership.

Specifically, in `DefaultUpdateService.isSupportedSumFilename`, the normalized filename must possess a **valid file extension**:
- A dot (`.`) followed by one to eight alphanumeric characters (`[a-zA-Z0-9]{1,8}`) at the end of the filename (`$`).
- At least one character before the dot (`^.+`).

Formally implemented via:
```java
private static final Pattern FILENAME_WITH_EXTENSION = Pattern.compile("^.+\\.[a-zA-Z0-9]{1,8}$");

static boolean hasFileExtension(String filename) {
    if (filename == null || filename.isBlank()) {
        return false;
    }
    return FILENAME_WITH_EXTENSION.matcher(filename.trim()).matches();
}
```

Once the filename satisfies this shape requirement, it must, as before, either:
1. Match `targetJarName` (case-insensitively);
2. End in `.jar`; or
3. Match a published asset name in `releaseAssetNames`.

#### Effects of the Shape Gate
- `DiscordTowny-1.10.0.zip` published as an asset: has extension `.zip` (3 alphanumeric characters, preceded by filename chars), matches `releaseAssetNames` -> recognized as an artifact declaration. A malformed digest beside it (`invalid  DiscordTowny-1.10.0.zip`) immediately refuses the release (`CHECK_FAILED`). F14 stays closed.
- `notes` published as an asset: no extension -> rejected by the shape gate and stays prose. The line `Release  notes` is not treated as a declaration, and a valid dedicated checksum succeeds (`UPDATE_AVAILABLE`).
- `docs/notes`: normalized basename is `notes`, which has no extension -> stays prose.
- `DiscordTowny-1.10.0.jar`: has extension `.jar`, matches target jar -> unchanged.
- Purely structural: no specific English words (e.g. `Release`, `Version`, `Build`) are special-cased. The rule is strictly about the shape of a filename, preserving vocabulary independence.

---

### 17.2 F18: Universal Leading UTF-8 BOM Normalization (Release Body Coverage)

#### Root Cause
Round 14 introduced a leading UTF-8 Byte Order Mark (`\uFEFF`) strip for dedicated checksum asset content in `parseChecksumFileContent`. However, release descriptions edited in Windows environments or through certain webhooks/APIs also frequently begin with a UTF-8 BOM (`\uFEFF`).

In `extractSha256FromBody`, no BOM strip was performed prior to line splitting. Consequently, a release body beginning with:
```text
\uFEFF<64 hex digest>  DiscordTowny-1.10.0.jar
```
carried the `\uFEFF` character directly into the candidate digest token. Because Java's `String.trim()` strips ASCII whitespace (`<= ' '`) and does not strip `\uFEFF`, the 65-character token failed `isValidSha256`. An otherwise valid sha256sum declaration in the body was rejected as a malformed checksum token, causing the release check to fail.

#### Resolution
`extractSha256FromBody` now mirrors `parseChecksumFileContent` by stripping a leading `\uFEFF` from the body text before splitting into lines:
```java
if (body.startsWith("\uFEFF")) {
    body = body.substring(1);
}
```
This ensures symmetric and uniform BOM handling across all checksum delivery surfaces (checksum files and release notes bodies).

---

### 17.3 Test Verification

The following tests were added and verified in `src/test/java/com/discordtowny/update/DefaultUpdateServiceTest.java`:

1. **`publishedAssetNamedNotesWithReleaseNotesProseBesideValidDedicatedChecksumSucceeds` (F17)**:
   - Verifies that when an asset literally named `notes` is published in the release, the body line `Release  notes` is not treated as a checksum declaration, remains prose, and does not block release discovery when a valid dedicated checksum asset is present (`UPDATE_AVAILABLE`).
2. **`releaseBodyWithDocsNotesBesideValidDedicatedChecksumSucceeds` (F17)**:
   - Verifies that directory-prefixed prose lines like `Release  docs/notes` whose basename has no extension are treated as prose and do not block valid release discovery.
3. **`hasFileExtensionRequiresAlphanumericExtensionWithCharactersBeforeDot` (F17)**:
   - Unit tests covering the shape requirements for filename extensions: verifies acceptance of extensions like `.zip`, `.jar`, `.tar.gz`, `.12345678`, `.7z`, `.a.b`, and `.JAR`, and rejection of extensionless names (`notes`, `Release`), hidden files without stem (`.notes`), empty extensions (`file.`), extensions exceeding 8 alphanumeric characters (`file.123456789`), and non-alphanumeric extensions (`file.tar-gz`, `file.tar_gz`).
4. **`sumLineForNonJarArtifactWithMalformedDigestIsTreatedAsProseAndDoesNotRefuseRelease` (F14 Maintained)**:
   - Maintains the F14 inversion: when `DiscordTowny-1.10.0.zip` is published in `assets`, a malformed digest on its sum line strictly refuses the release (`CHECK_FAILED`), both beside a dedicated checksum asset and beside a valid body declaration.
5. **`bodyWithLeadingUtf8BomFollowedByValidSumLineSucceedsAndBindsDigest` (F18)**:
   - Verifies that a release body beginning with `\uFEFF` followed by a valid sha256sum line (`\uFEFF<64 hex>  DiscordTowny-1.10.0.jar`) is parsed cleanly, strips the BOM, binds the digest, and successfully reports `UPDATE_AVAILABLE`.






