# Update changelog

The user message is:

```
develop-mr @docs/update-changelog-prompt.md
```

or

```
release-mr @docs/update-changelog-prompt.md
```

The first word is the mode. Run that mode. Ignore any extra words except the mode.

---

You are updating the project changelog for **payment-processing-system**. Follow [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/).

Changelogs are for humans. Do not paste git logs, file lists, or commit hashes. Write short bullets that say what changed and why it matters.

## Repo conventions

| Item | Value |
| ---- | ----- |
| Remote | GitHub `aaami1ster/payment-processing-system` |
| Integration branch | `develop` (feature MRs land here) |
| Release branch | `main` |
| Version source | `<version>` of this project in [`pom.xml`](../pom.xml) (not the Spring Boot parent version) — e.g. `0.0.1-SNAPSHOT` → release as `0.0.1` when cutting a release |
| Dev changelog | [`CHANGELOG_DEV.md`](../CHANGELOG_DEV.md) |
| Product changelog | [`CHANGELOG.md`](../CHANGELOG.md) |

## Modes

### `develop-mr`

Feature branch → `develop`.

Goal: `CHANGELOG_DEV.md` lists each develop MR separately so you can see which MRs are on `develop` and not yet on `main`, and what each one changed.

1. Diff: `git diff develop...HEAD`. That diff is **this MR only**. Do not changelog files that are already on `develop`.
2. Also read `git log develop..HEAD --oneline` only as context. Do not copy it.
3. Read `CHANGELOG_DEV.md` on `develop` (`git show develop:CHANGELOG_DEV.md`). Those MR sections are already merged to `develop`. Leave them unchanged. If the file does not exist on `develop`, treat prior MR sections as empty.
4. Update `CHANGELOG_DEV.md` only:
   - Keep every MR section that already exists on `develop`. Never merge this branch’s bullets into an older MR. Never replace another MR’s title.
   - Add or update **one** section for this branch, newest first, directly under `## [Unreleased]`.
   - If this branch already has a section (re-running `develop-mr`), replace that section’s title and bullets. Do not add a second section for the same branch.
   - This section’s bullets come only from `git diff develop...HEAD`. Skip anything already listed in a `develop` MR section.
5. Ensure `CHANGELOG.md` exists (Keep a Changelog skeleton with empty `## [Unreleased]` if missing). Do not add release versions during `develop-mr`.
6. Write this MR’s title on its section heading (see below). Also print it in the chat.

### `release-mr`

Release branch → `main`.

1. Diff: `git diff main...HEAD`
2. Read `CHANGELOG_DEV.md`.
3. Update `CHANGELOG.md`:
   - Keep `## [Unreleased]` at the top, empty (heading only).
   - Directly under it, add `## [X.Y.Z] - YYYY-MM-DD`. Use the release version from `pom.xml` project `<version>` (strip `-SNAPSHOT` if present) and today’s date.
   - Flatten every develop MR section into that version. Drop MR headings (`### MR: ...`). Merge bullets under `### Added` / `### Changed` / etc. Newest items at the top of each heading. Do not duplicate.
   - Add anything notable from the diff that is missing from `CHANGELOG_DEV.md`.
   - Write the release MR title under the new version heading.
4. Reset `CHANGELOG_DEV.md` to an empty `## [Unreleased]` section. Keep the file title and intro. Delete every MR section and its bullets.
5. Print the MR title in the chat.

## MR title

Use [Conventional Commits](https://www.conventionalcommits.org/) / Commitizen:

```
type: short summary
```

or with a scope:

```
type(scope): short summary
```

Types (pick one): `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.

Rules:

- Lowercase `type`. Imperative summary, no trailing period, about 50 characters.
- One type for the whole MR. If mixed, use the strongest: `feat` > `fix` > `refactor` > `chore`.
- Breaking change: `feat!:` or `feat(scope)!:`.
- `develop-mr`: describe the feature or fix (`feat: bootstrap phase 0 runtime foundation`).
- `release-mr`: `chore(release): X.Y.Z` using the version from `pom.xml` (without `-SNAPSHOT`).

`develop-mr` → each MR is its own section under `## [Unreleased]` in `CHANGELOG_DEV.md`. Newest MR first. Already-merged develop MRs stay below:

```
## [Unreleased]

### MR: `feat: bootstrap phase 0 runtime foundation`

#### Added

- Start a Spring Boot app with Compose Postgres and Mongo plus Liquibase schema

#### Security

- Add OWASP and Docker Scout scripts and fail the gate on High/Critical app findings

### MR: `docs: add Bruno API collections and plan`

#### Added

- Add Bruno health/user/transaction collections for manual API checks
```

Do not replace or rewrite other MRs. Only add a new section, or update this branch’s existing section.

`release-mr` → under the new version in `CHANGELOG.md`:

```
## [0.0.1] - 2026-09-12

MR title: `chore(release): 0.0.1`

### Added
### Security
```

Also print:

```
MR title: feat: bootstrap phase 0 runtime foundation
```

## Format

Use only these change-type headings, and only when that type has at least one item:

In `CHANGELOG.md` (under a version):

```
### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security
```

In `CHANGELOG_DEV.md` (under an MR section):

```
#### Added
#### Changed
#### Deprecated
#### Removed
#### Fixed
#### Security
```

Rules:

- Latest version first. ISO dates. Newest MR first under Unreleased. Newest bullets at the top of each heading.
- One line per item. Start with a verb. No trailing period unless the line has more than one sentence.
- Skip noise: formatting-only, generated files, lockfile-only, WIP, merge commits, package-info stubs with no behaviour.
- Never write secrets, tokens, passwords, IPs, NVD API keys, or `.env` values.
- Do not add empty headings.
- Keep comparison links at the bottom of each changelog file.
  - `CHANGELOG.md`: `[unreleased]: https://github.com/aaami1ster/payment-processing-system/compare/main...HEAD`. For a new version, add `[X.Y.Z]: https://github.com/aaami1ster/payment-processing-system/compare/vPREV...vX.Y.Z` when a previous tag exists; if this is the first version, link to `https://github.com/aaami1ster/payment-processing-system/releases/tag/vX.Y.Z` or omit version links until tags exist.
  - `CHANGELOG_DEV.md`: `[unreleased]: https://github.com/aaami1ster/payment-processing-system/compare/develop...HEAD`.

## Example item

```
#### Security

- Add OWASP Dependency-Check and Docker Scout scripts for every phase validation
```

Edit the markdown files. Do not commit unless asked.
