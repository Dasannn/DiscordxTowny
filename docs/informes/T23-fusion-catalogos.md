# T23 Repair Report — Catalog Merge After Review

This report documents the resolution of all findings (F1 through F9) from `docs/revisiones/T23-fusion-catalogos.md`.

## Summary of Changes

All regular expressions for locating sections, keys, and structural boundaries were removed. Catalog inspection and insertion calculations are now driven directly by SnakeYAML's node composition (`yaml.compose(new StringReader(ownerContent))`). Concrete text splices are calculated using the 0-indexed line and column marks carried by `Node` objects (`getStartMark()` and `getEndMark()`).

---

## Finding-by-Finding Resolution

### F1 — Text scanning mistakes scalar contents for YAML structure
- **What changed**:
  Removed regex header search (`findSectionHeaderIndex`, `findFirstSectionIndex`). Replaced with SnakeYAML's composed `MappingNode` parse tree. The root mapping nodes identify true top-level section keys exclusively. `Node.getEndMark()` on values (including multiline quoted scalars) determines the exact terminal line of existing entries.
- **Why it closes F1**:
  Lines like `general:` appearing inside single- or double-quoted scalar values (e.g. `owner-note`) are recognized as scalar content within their parent node and cannot be selected as section headers. Unindented quoted continuations belong to the scalar's span; insertions are placed strictly after `valueNode.getEndMark()`.

### F2 — Inserting before trailing blank lines changes block scalar values
- **What changed**:
  Insertion offsets now use `endMark = valNode.getEndMark()`. For block scalars (e.g. `|+`), the end mark accounts for consumed trailing newlines. The last line occupied by the node is determined via:
  `lastLine = (endMark.getColumn() == 0 && endMark.getLine() > valNode.getStartMark().getLine()) ? endMark.getLine() - 1 : endMark.getLine();`
  and insertion takes place at `lastLine + 1`.
- **Why it closes F2**:
  Trailing blank lines belonging to block scalars are not skipped or split. New keys are inserted after the entire scalar block, preserving the exact newline count and chomping semantics of the owner's scalar.

### F3 — Present null values treated as absent keys
- **What changed**:
  Replaced Bukkit's `ownerYaml.contains(...)` with structural presence checks over SnakeYAML's `MappingNode` child tuples. A key defined with an empty, null, or tilde value (`no-permission:`, `no-permission: null`, `no-permission: ~`) is registered in `ownerSec.keys`. Similarly, a present but empty section header (`general:`) is detected in `ownerSections`.
- **Why it closes F3**:
  Present null or empty keys are recognized as existing owner entries and are excluded from `missingKeys`, preventing duplication. Empty sections are not treated as absent sections and are not appended as duplicate sections at EOF.

### F4 — Non-atomic move fallback on unsupported atomic move
- **What changed**:
  In `YamlConfigLoader.writeAtomically` and `moveFile`, if `AtomicMoveNotSupportedException` or `UnsupportedOperationException` is encountered during `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`, the method logs a warning (`messages_en.yml: atomic move not supported; catalog merge abandoned`) and throws an `IOException` without calling a non-atomic move fallback. `finally` deletes the temporary file, and `mergeDefaultFile` catches the exception, abandoning the merge.
- **Why it closes F4**:
  The merge is abandoned with the original file untouched whenever atomic replacement cannot be guaranteed. No secondary non-atomic overwrite is ever attempted.

### F5 — Duplicate section ambiguity and unverified reported counts
- **What changed**:
  1. Top-level section keys and section child keys are checked for duplicates during node tree traversal. If a section or key is defined more than once in the owner file, a warning is logged (`<resource>: duplicate section '<name>'; catalog merge aborted`) and the merge immediately returns without writing.
  2. Section headers with YAML escape sequences or quotes (e.g. `"\x67eneral":`) are naturally decoded by `ScalarNode.getValue()`.
  3. After generating `updatedContent`, `verification.loadFromString(updatedContent)` verifies the candidate. Only keys verified to be present and resolvable in `verification` are collected into `verifiedAdditions`. If empty, nothing is written. The warning report is emitted only after `writeAtomically` succeeds on disk and reflects only verified additions.
- **Why it closes F5**:
  Duplicate section definitions are rejected safely without modifying the file. The logged missing-key report reflects exclusively those keys that were installed and verified on disk.

### F6 — Preserving original line terminators and EOF boundary
- **What changed**:
  Replaced raw string splitting with a `Line` abstraction (`content` and `lineBreak`). Original lines retain their exact original line terminators (`\r\n`, `\n`, `\r`, or empty `""` for unterminated EOF). Inserted sibling lines inherit the line terminator of the preceding line. When insertions occur in the middle of a file, the final line's EOF terminator is untouched.
- **Why it closes F6**:
  Mixed line endings in an owner file are preserved without converting to a uniform delimiter. Files lacking a final newline do not gain one unless an insertion occurs directly at EOF.

### F7 — Testing domain invariant (town, nation, resident)
- **What changed**:
  Repaired `CatalogMergeTest.invariantsTownNationResidentStayUntranslatedInBothCatalogs`:
  1. Verified that bundled Spanish catalogs do not translate domain terms in prose (asserts absence of `ciudad`, `pueblo`, `residente`, `nación` across relevant entries).
  2. Exercised the live merger with Spanish configuration, restoring missing keys on disk and through `Messages.plain()`, verifying that restored messages retain `town` and `resident` in prose and placeholders.
- **Why it closes F7**:
  The test directly exercises the loader and merger, asserting both prose domain terms and placeholder preservation on actual restored output.

### F8 — Exact Spanish restoration and disk persistence verification
- **What changed**:
  Repaired `YamlConfigLoaderTest.keyDeletedFromTheSpanishFileComesBackInSpanishRatherThanEnglish`:
  1. Replaced substring checks with exact assertion on rendered runtime message: `assertEquals("[DiscordTowny] El espacio de Roma está listo en Discord.", text)`.
  2. Loaded the restored `messages_es.yml` from disk, retrieved `space.created`, and compared it directly against the bundled Spanish catalog entry (`assertEquals(bundledEs.getString("space.created"), restoredOnDisk)`).
  3. Also strengthened `firstRunWritesBothFilesAndNeverOverwritesAnOwnerEdit` to verify complete initial catalogs and restored disk keys.
- **Why it closes F8**:
  Falsifiable against in-memory-only fallbacks, wrong message content, and non-specific key matches like `admin.info-created:`.

### F9 — Comprehensive failure and boundary test coverage
- **What changed**:
  Added comprehensive tests in `CatalogMergeTest.java`:
  - `sectionHeaderInsideQuotedValueIsNotMistakenForSection` (Example A / F1)
  - `unindentedQuotedContinuationDoesNotEndScanEarly` (Example B / F1)
  - `blockScalarWithTrailingBlankLinesPreservesBlankLines` (Example C / F2)
  - `presentNullOrEmptyKeyIsNotTreatedAsAbsent` (Example D / F3)
  - `emptySectionIsNotAppendedAsDuplicateSection` (F3)
  - `duplicateSectionAbortsMergeLeavesFileUntouchedAndWarns` (Example E / F5)
  - `escapedOrQuotedSectionHeaderIsHandledCorrectly` (F5)
  - `preservesMixedLineEndings` (F6)
  - `fileWithNoFinalNewlineDoesNotGainOneWhenInsertionIsInMiddle` (F6)
  - `atomicMoveNotSupportedAbandonsMergeAndWarns` (F4 / F9)
  - `writeFailureLeavesTargetUntouchedAndCleansUpTempFile` (F9)
  - `legalTabsInQuotedValuesArePreserved` (F9)
  - `unusualIndentationFourSpacesIsRespected` (F9)
  - Repaired `preservesWindowsCrlfLineEndings` to establish successful addition on CRLF input.
  - Repaired `secondLoadDoesNotRewriteOrEmitDuplicateWarnings` to check file completeness, age timestamp, and check byte equality.
  - Repaired `wholeSectionDeletedIsRestored` to assert restoration of all section keys.
- **Why it closes F9**:
  Every edge case, failure mode, and preservation invariant identified in the review is explicitly tested with assertions that fail if the defect is reintroduced.

---

## Round 3 — Resolution of the Five Failing Tests

The architect compiled and executed the test suite after Round 2, reporting 662 tests passed and 5 failed. Below is the triage, root-cause analysis, and resolution for each of the five failures.

### 1. `sectionHeaderInsideQuotedValueIsNotMistakenForSection`
- **What it turned out to be**:
  Test expectation error. Per YAML 1.2 §6.5 and §7.3.2, multiline single-quoted scalars fold line breaks into space characters unless an empty line intervenes. The string `'start\ngeneral:\nend'` folds into `"start general: end"`. The merger parsed and preserved this correctly; the test assertion was mistakenly expecting unfolded literal line breaks (`"start\ngeneral:\nend"`).
- **What changed**:
  Updated the expected value in `assertEquals("start general: end", parsed.getString("owner-note"))`. Strengthened assertions to verify that no catalog lines were spliced into `owner-note` (checking exact literal contents in `merged`), that the real section `general:` received `resident-not-found:`, that runtime message lookup resolves correctly, and that the addition warning is logged.

### 2. `unindentedQuotedContinuationDoesNotEndScanEarly`
- **What it turned out to be**:
  Test expectation error. As with issue 1, YAML 1.2 §6.5 folds newlines within single-quoted scalar continuations (`'start\ncontinuation'`) into `"start continuation"`. The merger handled the unindented continuation properly, but the assertion expected `"start\ncontinuation"`.
- **What changed**:
  Updated the expected value to `"start continuation"`. Strengthened assertions to check that the literal multiline scalar remains uncorrupted in `merged`, that `resident-not-found:` is placed strictly after the continuation quote, that the missing key resolves at runtime, and that the warning is emitted.

### 3. `blockScalarWithTrailingBlankLinesPreservesBlankLines`
- **What it turned out to be**:
  Assertion expectation mismatch with fixture. The fixture `general:\n  no-permission: |+\n    Owner line\n\n\n` contains three newline characters after `Owner line`. Per YAML 1.2 §8.1.1.2 (keep chomping indicator `|+`), all trailing line breaks are preserved in the scalar value, resolving to `"Owner line\n\n\n"`. Both before and after the merge, the scalar resolves to `"Owner line\n\n\n"`; no line breaks were added or truncated by the merger. The assertion had expected `"Owner line\n\n"` (which corresponded to Example C's 2-newline input from the review).
- **What changed**:
  1. Updated the assertion to verify that `parsed.getString("general.no-permission")` matches the pre-merge parsed value `original.getString("general.no-permission")` and equals `"Owner line\n\n\n"`.
  2. Maintained the boundary checks: `between.contains("\n\n\n")`, byte-for-byte presence of `no-permission: |+\n    Owner line\n\n\n`, and presence/resolution of `resident-not-found:`.
  3. Added a dedicated test `blockScalarWithTwoTrailingNewlinesPreservesBothLines` testing Example C's 2-newline fixture, verifying `"Owner line\n\n"`.

### 4. `fileWithNoFinalNewlineDoesNotGainOneWhenInsertionIsInMiddle`
- **What it turned out to be**:
  Flawed test fixture violating test precondition. The test fixture only provided `prefix`, `general`, and `linking`, omitting `space`, `sync`, `updates`, `admin`, `embed`, `help`, and `status`. Under the catalog merge contract (spec 9.1 and `wholeSectionDeletedIsRestored`), the merger restored all seven absent sections at EOF. Because new content was appended at the end of the file, the file ended with the restored sections (which end with a newline), preventing the test from verifying an insertion purely in the middle. When an insertion is genuinely in the middle, `YamlConfigLoader` already preserves the EOF boundary without adding a final newline.
- **What changed**:
  1. Updated the test fixture to include all catalog sections, omitting only `general.resident-not-found`, and placing `linking:` at the end with `link-success: 'OK'` as the final line with no newline terminator.
  2. All original assertions (`merged.contains("resident-not-found:")`, `!merged.endsWith("\n")`, `!merged.endsWith("\r")`, and `merged.endsWith("link-success: 'OK'")`) now execute against a genuine middle insertion and pass cleanly.
  3. In `YamlConfigLoader.java`, guarded the EOF splice logic so that section-separating blank lines are only inserted when appending absent sections (`appendingAtEof = !absentSections.isEmpty()`), ensuring that child keys added to a section at EOF never receive an unwanted preceding blank line.

### 5. `presentNullOrEmptyKeyIsNotTreatedAsAbsent`
- **What it turned out to be**:
  Test line filter false positive. The fixture contained `general.towny-read-failed: null`. The merger correctly recognized this key as present and did not duplicate it. However, the test counted occurrences via `merged.lines().filter(l -> l.contains("towny-read-failed:")).count()`. Because the restored `sync:` section includes `cause-towny-read-failed: "Towny read failed"`, the substring check matched both lines, yielding a count of 2.
- **What changed**:
  1. Updated the test filter to `l -> l.stripLeading().startsWith("towny-read-failed:")` (and similarly for `no-permission:` and `no-towns-found:`), matching the actual YAML key definition rather than substring occurrences within other key names.
  2. Added assertions verifying that none of the present null/empty keys appear in the missing keys warning report.
  3. In `YamlConfigLoader.java`, updated the verification loop to accept `verification.contains(key) || verification.isSet(key)`.
