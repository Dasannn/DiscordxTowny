# T24 — Eleventh review

Scope: `5a631d2..76f5ef7` on `fix/updater-delivery-host` (`76f5ef7`). Review only. Gradle was not run; the architect reported the suite green on this tip.

## Finding

### F22 — A keyword in an unsupported filename escapes the declaration audit (named follow-up)

**File and line:** `src/main/java/com/discordtowny/update/DefaultUpdateService.java:1425-1429, 1458-1466`.

The agreed rule makes a `sha256` keyword anywhere on a body line an unconditional declaration signal, including in a filename's directory prefix. The sum branch instead requires `supportedFile` for both the keyword and hash-shaped arms. For `invalid  sha256/other.zip` when `other.zip` is not a release asset, the sum branch skips validation. `KEYWORD_AUDIT_PATTERN` then deliberately excludes `sha256/` in a path, so the line leaves no invalid outcome. Beside a valid dedicated checksum for the jar, the release is offered. The existing path-prefix test uses the target jar, which passes `supportedFile` and misses this case.

**Correct behavior:** Refuse any keyword-bearing malformed body declaration, regardless of whether its filename names a release artifact. Apply the artifact gate only to the hash-shaped arm, or make the keyword audit catch an unconsumed keyword-bearing sum line. Add one end-to-end case with `invalid  sha256/other.zip`, an unpublished `other.zip`, and a valid dedicated jar checksum.

## Contract and remaining checks

The narrower contract resolves F19 and F20: `Release  notes.txt` and `invalid  launcher` are prose, while a hash-shaped broken token naming an artifact is a declaration. The F14 inversion and new prose tests match that decision. `isHashShaped` implements the stated inclusive length bounds, ASCII alphanumeric restriction, and `hexCount * 4 >= length * 3` rule. A shorter truncation, a token containing punctuation, or one with fewer than three quarters hex characters becomes prose by the explicit contract; a long hex-heavy prose token can be classified as a declaration. Neither case can supply a valid checksum unless the token is exactly 64 hex characters.

The per-line U+FEFF handling covers whitespace, a Markdown list prefix, and outer quotes before parsing; the leading whole-body strip is redundant but harmless. No new safety path was found: `parseRelease` requires a valid checksum from an asset or body bound to the selected jar, refuses invalid or conflicting evidence it recognizes, and `download` compares the streamed jar's SHA-256 with the selected checksum before publication. F22 can fail to veto a bad line, but it cannot authorize an unverified jar.

**Verdict: integrate with named follow-ups.** Follow up on F22 and its focused regression test; it is a narrow violation of the keyword declaration contract without an unverified-install path.
