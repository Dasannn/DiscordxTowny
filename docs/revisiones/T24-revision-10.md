# T24 — Tenth review

Scope: `01f84cd..5a631d2` on `fix/updater-delivery-host` (`5a631d2`). Review only; no build run, as instructed.

## Findings

### F19 — Extension-shaped prose still causes false refusal (blocking)

**File and line:** `src/main/java/com/discordtowny/update/DefaultUpdateService.java:75-76, 111-142, 1399-1412`; `src/test/java/com/discordtowny/update/DefaultUpdateServiceTest.java:5451-5544, 5642-5665`.

The new shape check only moves the collision from `notes` to names such as `notes.txt`. If a release publishes `notes.txt` and its body says `Release  notes.txt`, `SUM_DECL_PATTERN` captures `Release` as the digest, `hasFileExtension` passes, asset membership passes, and validation returns `InvalidOrAmbiguous`. A valid dedicated checksum for the jar is then discarded by `parseRelease` as `CHECK_FAILED`. The same happens with `Version  1.2` and a published `1.2`, `See  docs/notes.txt` and a published `notes.txt`, or `Download  https://example.test/notes.txt` because `normalizeFilename` reduces the last two subjects to `notes.txt`. A sentence ending in a filename can therefore block an authentic update. The new integration tests cover only extensionless `notes` and `docs/notes`; the direct `hasFileExtension` test asserts the regex rather than this prose contract.

**Correct behavior:** Ordinary prose must not become a malformed checksum solely because its last field has an extension and matches a release asset. Keep refusing an actual malformed declaration for a published `.zip`; exercise both outcomes through `checkForUpdate`, including a prose line that names a published file with an extension. Since `Release  notes.txt` and `invalid  notes.txt` have the same two-field shape, the current syntax provides no unambiguous way to infer intent from filename shape and membership alone. The rule needs an explicit declaration signal or a documented narrower promise.

### F20 — Broken declaration for an extensionless published artifact is ignored (blocking)

**File and line:** `src/main/java/com/discordtowny/update/DefaultUpdateService.java:126-128, 1406-1424`.

For a release publishing an extensionless artifact named `launcher`, the body line `invalid  launcher` is a sha256sum-shaped declaration for that artifact. The new gate rejects `launcher` before `validateDeclaration`; the keyword audit finds no `sha256` label. If a valid jar checksum exists elsewhere, the release is accepted. The same bypass applies to published names with a trailing period or extensions longer than eight characters. T24 requires a malformed body declaration to refuse the release regardless of which artifact it names. The new tests do not cover an extensionless published artifact with a broken digest.

**Correct behavior:** A recognizable broken checksum declaration for a published artifact must fail even when that artifact has no extension. An extension is not a property required of a GitHub release asset or of a sha256sum filename.

### F21 — Indented or list-prefixed body BOM still corrupts a valid sum (named follow-up)

**File and line:** `src/main/java/com/discordtowny/update/DefaultUpdateService.java:1340-1342, 1350-1356`; `src/test/java/com/discordtowny/update/DefaultUpdateServiceTest.java:5598-5640`.

Only `body.startsWith("\uFEFF")` strips U+FEFF. In `"  \uFEFF<64 hex>  DiscordTowny-1.10.0.jar"` or `"- \uFEFF<64 hex>  DiscordTowny-1.10.0.jar"`, `trim()` or the list-prefix removal leaves U+FEFF attached to the digest. The filename passes the gate and validation refuses an otherwise valid checksum. The new test asserts only a BOM at character zero. U+FEFF is the relevant decoded Unicode marker; a supposed second UTF-8 BOM spelling is not needed to explain this case. After preceding whitespace it is no longer a file-start encoding mark, so this is narrower than F19 and F20.

**Correct behavior:** If body normalization intends to tolerate a pasted or indented U+FEFF before the first declaration, strip it after line whitespace and Markdown list prefixes are removed, and assert the resulting update outcome. Otherwise document that support is limited to a BOM at body character zero.

## Other checks

`normalizeFilename` replaces backslashes and takes the final path component, so directory prefixes and URL paths can acquire the same basename as a published asset. It does not remove a leading `*` or trailing period. A single-space binary sha256sum marker (`<hash> *file.zip`) is consumed by `SUM_DECL_PATTERN`; a two-space-plus-`*` form leaves the star on the captured filename and may miss membership. That behavior predates this diff. Names made entirely of dots do not pass the new extension regex. The body-start U+FEFF test does assert the end-to-end accepted checksum, so F18's exact reported case is closed.

**Verdict: blocked.** F19 can make a valid update unavailable, and F20 violates the malformed-declaration rule in the opposite direction. F21 is narrow enough for a named follow-up, but the extension rule still needs a deliberate contract decision. Another regex refinement based only on filename shape will repeat the ambiguity.
