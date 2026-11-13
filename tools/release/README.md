# Release tools

Everything a release needs after the code is merged. Porting a change to a support line is
`tools/lines/lines.py port` ([support lines](../../docs/operations/support-lines.md)). Nothing here is edited per release: each
tool takes `--version` and reads the rest from the tags.

| Tool | What it does |
| --- | --- |
| `gate.sh <worktree>` | The local release gate for one line: catalog checks, the full Gradle build with gametests on a private display, `release_check.py`, artifact checks, the cross-line check and the Python suites. Keeps every step's log, the JUnit totals and the built jars. |
| `gate-locked.sh <worktree>` | The same, holding the shared harness lock. Use it whenever anything else may be building. |
| `tag_chain.sh <version>` | Pushes the tags one at a time, oldest line first and the Latest line last; watches each release run and verifies it before the next. |
| `verify_lss.py --version V <line>` | Checks a published line: GitHub release, Modrinth versions, and that the published jars equal the gated ones entry by entry. |
| `recover_modrinth.py --version V <line>` | Finishes the Modrinth uploads of a release run that failed after its GitHub step, from the attached jars. |
| `publish_vss.py --version V --previous-state <dir>` | Publishes the gated Voxy Server Side jars (stable releases only). |
| `publish_curseforge.py --version V` | Uploads the Fabric and NeoForge jars to CurseForge. |

The publish tools print their plan by default and act only with `--publish`.

## Where things are kept

- Gate results: `~/.local/state/lss-release-prep-v<version>/gates/<line>/` (`steps.txt`,
  `summary.json`, `jars/`). `publish_vss.py` also reads `gates/manifest.json`, a copy of the
  release-preparation manifest from `docs/implementation/`.
- Publish receipts and logs: `~/.local/state/lss-publish-v<version>/`.
- Secrets: the file named by `LSS_SECRETS_FILE` (default `~/.bot.env`), keys `MODRINTH_PAT` and
  `CURSEFORGE_API_TOKEN`. No tool prints them.

## Order of a release

1. Gate every shipping line on its final commit (`gate-locked.sh`), then record the results.
2. Merge the release PRs, create the annotated tags locally (`git tag -a --cleanup=verbatim`).
3. `tag_chain.sh <version>`. A beta ships two lines: `LSS_RELEASE_LINES=1.21.1,26.3`. It refuses a
   support line whose sync point is behind the release commit on main.
4. A stable release then runs `publish_vss.py` and `publish_curseforge.py`.

A version with a hyphen (`0.22.0-beta.1`) is a prerelease: Modrinth type `beta`, a GitHub
prerelease, never Latest, not featured, and not published to VSS.

## Rules that are not negotiable

- A release run that failed after publishing anything is never re-run. Recover from its attached
  artifacts.
- Tags are pushed one per push.
- A red gate step is explained, not retried, unless its signature is in
  `docs/operations/test-flakes.md`.
