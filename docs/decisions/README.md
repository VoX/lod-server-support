# Decisions

One short record per decision that shapes the project: what was decided, why, what follows from
it, and what would reopen it. A record is at most 40 lines. History and evidence stay in the
plans under `docs/planning/` and in the commits.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-line-tiers.md) | Lines are tiered by who uses them | accepted 2026-11-13 |
| [0002](0002-beta-first-releases.md) | Releases go out as a beta first | accepted 2026-11-13 |
| [0003](0003-protocol-dialect-usage.md) | Old protocol support is measured before it is judged | accepted 2026-11-13, amended |
| [0004](0004-far-plane-default.md) | Far players are drawn to their full range by default | accepted 2026-11-13, corrected; one part open |
| [0005](0005-command-roots.md) | The Voxy Server Side client also answers to `/lss` | accepted 2026-11-13, narrowed twice |
| [0006](0006-one-tree-for-26x.md) | The 26.x lines share one tree | accepted 2026-11-13, not built yet |
| [0007](0007-retry-rule.md) | A red test is explained, not retried | accepted 2026-11-13 |
| [0008](0008-main-is-the-reference.md) | Main is the reference; support lines record one sync point | accepted 2026-11-13 |
| [0009](0009-settings-stay-strict.md) | A settings file with a mistake does not load, and says so loudly | accepted 2026-11-13 |

Status values: `proposed`, `accepted`, `superseded by NNNN`, `withdrawn`.
