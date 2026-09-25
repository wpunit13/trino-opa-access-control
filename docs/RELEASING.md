# Releasing trino-opa-access-control

Runbook for cutting a release. The model: **the tag is the only version
decision; the release workflow stamps it at build time.** If you remember only
one rule, make it **Rule 1** below.

---

## 1. Base Rule

**Never hand-edit the POM version. Ever.**

The version changes only inside the release workflow, derived from the pushed
tag. `main` stays at its dev-marker version (`0.1.0-SNAPSHOT`) permanently — it
is never published. The version-hygiene CI guard (`scripts/check_version_hygiene.sh`,
run as the `version-hygiene` check on every PR) fails any PR that touches a
`<version>` line. Escape hatch: a commit whose subject contains
`[version-bump]` — for legitimate dependency upgrades, or the one-time change of
the dev marker itself.

---

## 2. The version model (what lives where)

| Place | Version | Who sets it |
|---|---|---|
| `main` (day to day) | dev marker: `0.1.0-SNAPSHOT` | Nobody — frozen |
| The tag `vX.Y.Z` | exact `X.Y.Z` | You, at release time |
| Maven Central / GitHub Release | `X.Y.Z` | The workflow, stamped from the tag |

One decision (the tag), one derivation (the stamp). No release commits, no
post-release bumps, no POM/tag reconciliation — the tag cannot diverge from the
published version because the published version **is** the tag.

---

## 3. The ritual (copy-paste)

1. Confirm CI is green on the `main` commit you want to release.
2. Create the tag on `main`:
   - GitHub UI → Releases → Draft a new release → new tag `vX.Y.Z` → target
     `main` → Publish; or
   - `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. Watch the `Release` workflow: **guard → release**, both green.
4. **First release only:** approve the deployment in the Central Portal UI
   (`autoPublish=false`, see §4).

That is the whole release. No local commits, no branch, no PR — releases never
push to `main`.

Choosing the number (`X.Y.Z`):

| Change since last release | Bump | Example |
|---|---|---|
| Bug fixes only | patch | `0.1.0 → 0.1.1` |
| New capability, new config key (additive) | minor | `0.1.0 → 0.2.0` |
| Changed/removed public behavior, or a change to the wire contract | major | `0.1.0 → 0.2.0` while pre-1.0, `1.0.0 → 2.0.0` after |

While the project is pre-1.0, breaking changes are permitted in a minor bump.
Once `1.0.0` ships, the wire contract (`schema_version`) and the config keys are
public API and a change to either is a major bump.

---

## 4. What the tag triggers

1. **guard** — two checks, both enforced because Central is immutable:
   - `GITHUB_REF_NAME` must match `^v[0-9]+\.[0-9]+\.[0-9]+$`. The trigger glob
     `v[0-9]*.[0-9]*.[0-9]*` alone is **not** numeric — `v0.1.0-rc1` matches it.
     Non-matching tags skip silently (exit 0, not a failure).
   - The tagged commit must be an ancestor of `origin/main` (releases are cut
     from `main` only).
2. **release** — checks out the tagged commit, stamps the POM version from the
   tag in the workspace (`mvn versions:set`, never committed), builds both jars,
   runs the conformance gate, creates the GitHub Release with checksums, then
   GPG-signs and deploys to Maven Central.

**First release only:** the deployment lands in the Central Portal for **manual
review** (`autoPublish=false` + `waitUntil=validated` in the root POM). Approve
it in the Portal UI. Then flip `autoPublish` to `true` **and** `waitUntil` back
to `published` in a LATER change — never in the change that first publishes.
(`waitUntil=published` with `autoPublish=false` would never be satisfied and the
build would hang; the enum is only `uploaded`/`validated`/`published`.)

---

## 5. Recovery

`workflow_dispatch` on the `Release` workflow is the recovery hatch. Give it the
version (`X.Y.Z`, which must match an existing `vX.Y.Z` tag on `main`) and it
runs the same guard + release path against that tag.

Use it when a stage failed for a reason you have since fixed on `main`:
**re-running the original run would replay the OLD workflow file**, so a
corrected workflow would not take effect. The dispatch entry point uses the
workflow file as it exists on the default branch now.

---

## 6. Guard rails — things that would hurt

- **Central is immutable.** A bad release is fixed by a *new* version, never by
  re-publishing. Never move or delete a pushed `vX.Y.Z` tag.
- **Never release from a non-green `main`.** The guard enforces that the tag's
  commit is on `main`; greenness is your judgment call.
- **Never publish pre-release tags.** `v0.1.0-rc1` is rejected by the guard. If
  pre-releases are ever wanted, that is a deliberate workflow change, not a
  tag-time improvisation.
- **Secrets never enter the repo** — they live in GitHub repository settings
  (see §7).
- **`main` is protected.** No direct pushes; everything lands via a PR with both
  CI legs and `version-hygiene` green.

---

## 7. One-time human prerequisites (before the very first release)

1. **Maven Central Portal** account with the verified namespace
   `io.github.wpunit13`.
2. **Four GitHub repository secrets** (Settings → Secrets and variables →
   Actions): `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` (Central Portal token),
   `GPG_PRIVATE_KEY` (armored), `GPG_PASSPHRASE`.
3. The GPG public key published to a public keyserver, so Central can validate
   the signatures.
