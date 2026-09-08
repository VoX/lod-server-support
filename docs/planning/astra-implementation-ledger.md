# Astra implementation ledger

Started 2026-09-08. Scope: the detailed [implementation plan](astra-system-review-fix-plan.md), all five support lines. Three Astra plan challenges completed; corrections incorporated. Implementation underway. Review artifacts: `/home/vox/.local/state/lss-review/20260908-implementation/`.

## Work packages

| WI | Status | Implementation / evidence | Remaining |
| --- | --- | --- | --- |
| 1 | IN PROGRESS | — | Review, implementation, affected-line tests and applicable runtime gates |
| 2 | IN PROGRESS | — | Review, implementation, affected-line tests and applicable runtime gates |
| 3 | IN PROGRESS | — | Review, implementation, affected-line tests and applicable runtime gates |
| 4 | IN PROGRESS | — | Review, implementation, affected-line tests and applicable runtime gates |
| 5 | IN PROGRESS | — | Review, implementation, affected-line tests and applicable runtime gates |
| 6 | NOT STARTED | — | Review, implementation, affected-line tests and applicable runtime gates |
| 7 | IN PROGRESS | — | Review, implementation, affected-line tests and applicable runtime gates |
| 8 | NOT STARTED | — | Review, implementation, affected-line tests and applicable runtime gates |
| 9 | NOT STARTED | — | Review, implementation, affected-line tests and applicable runtime gates |
| 10 | NOT STARTED | — | Review, implementation, affected-line tests and applicable runtime gates |
| 11 | NOT STARTED | — | Review, implementation, affected-line tests and applicable runtime gates |
| 12 | NOT STARTED | — | Review, implementation, affected-line tests and applicable runtime gates |
| 13 | IN PROGRESS | — | Review, implementation, affected-line tests and applicable runtime gates |
| 14 | NOT STARTED | — | Review, implementation, affected-line tests and applicable runtime gates |
| 15 | NOT STARTED | — | Review, implementation, affected-line tests and applicable runtime gates |

## Line baselines

| Line | Target | Starting merged commit | Implementation branch |
| --- | --- | --- | --- |
| 1.21.1 | support/mc1.21.1 | `1b544494d66e` | `fix/astra-review-mc1.21.1` |
| 1.21.10 | support/mc1.21.10 | `d08faa91428d` | `fix/astra-review-mc1.21.10` |
| 1.21.11 | support/mc1.21.11-v0.14 | `da726358f2c0` | `fix/astra-review-mc1.21.11` |
| 26.1 | support/mc26.1-v0.14 | `8e900e69e8ce` | `fix/astra-review-mc26.1` |
| 26.2 | main | `2b2a0df728e2` | `fix/astra-review-mc26.2` |

## Validation rules

Record exact commands, outcome, reused results, unexpected skips and evidence paths. Do not count historical probe failures or jar inspection as fixed-code validation. Do not run soaks concurrently with builds/tests. No release tag or publication is part of this implementation.
