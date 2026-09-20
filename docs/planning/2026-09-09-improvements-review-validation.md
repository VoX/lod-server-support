# Validation and runtime plan review

Date: 2026-09-09. Scope: independent read-only review of the project-improvements implementation plan, chiefly P4, P6 and final acceptance. Inspected current code/docs in the 1.21.1 and 26.2 worktrees; no builds, runtime launches, network operations or product/plan changes. Recommendations remain within original items 1–5, 9 and 10.

Disposition: revise the following acceptance contracts before implementation. The overall scope is coherent, and the explicit exclusion of new wire/public consumer APIs, repair features and publishing redesign should remain.

## V1 — MAJOR: Windows process ownership needs an implementable backend contract

Sections: §8 P4 ownership/backend behavior; §14 cleanup.

Evidence: `26.2/scripts/lib/owned-process.py` explicitly depends on Linux `/proc`, `prctl(PR_SET_CHILD_SUBREAPER)`, parent-death signaling and process groups. It fails closed without those facilities. The current plan correctly separates Windows Prism from headless launching, but then promises owned-descendant termination and recovery for both without identifying how a Windows JVM belongs to the controller. Launching Prism through WSL does not establish that guarantee for native Windows descendants or an existing launcher process. “POSIX headless” also overstates the portability of the existing Linux helper.

Amendment: call the reused backend Linux headless. Add a bounded Windows ownership feasibility gate before `rig run`: a dedicated disposable launcher instance and a native ownership mechanism (for example a tested Job Object helper), with start identity, controller-death cleanup and escaped/nested-child controls. Reject automation when the backend cannot prove ownership; record such observer runs as manual with explicitly witnessed teardown. Include a separate actual Windows backend test, not only Linux script tests. A port or command-line match must never establish ownership. This is fixture/tooling work, not product work.

## V2 — MAJOR: Multi-client/region feasibility must precede the final performance lane

Sections: §8 P4; §11 P6b; §13 sequencing.

Evidence: `26.2/fabric/build.gradle:245–251` fixes the existing soak client to `build/run/soak-client` and `SoakPlayer`; `scripts/soak.sh:623` launches its client runs sequentially. `PaperSoakScenarioDriver` anchors joins by ordinal and owns its timeline on a global-region pump. The historical Folia ledger expressly says the accepted cache-clear scenario proves neither a second TCP login nor concurrent multi-region operation. Existing four-scheduler availability in plain Paper does not prove actual Folia region separation.

The plan recognizes distinct clients and simultaneous regions but leaves the main new harness substrate and “platform-safe instrumentation” undefined until late P6b. Neither distinct thread IDs nor overlapping async tasks alone prove that two player regions tick independently; thread reuse and global/async tasks can mislead. A blocking barrier inside a region tick would manufacture the overlap and contaminate the performance measure.

Amendment: add an early P4/P6b feasibility milestone on pinned Folia 26.2: two independent clients with unique offline identities, per-client game/cache/output directories and run-scoped connection IDs; verified simultaneous registrations; correctly routed join/churn timelines. Specify the actual region ownership/tick API or inspected server descriptor to be used, its legal calling context, identity lifetime during region split/merge and a nonblocking overlap proof. Record monotonic start/end samples for real owning-region work plus ownership assertions. Require a negative control that collapses both subjects into one region and is rejected. Only then expand to four clients and commit to the final measurement lane. Unresolved instrumentation availability remains unknown, not a presumed implementation detail; never add a public delivery API to solve it.

## V3 — MAJOR: Performance metrics and pass statistics are not yet reproducible

Sections: §7 P1 profiling; §10 P5 comparison; §11 P6b performance; §12 preset derivation.

Evidence: `26.2/paper/.../soak/PaperSoakMetricsExporter.java:352–357` exports wall elapsed divided by global driver tick count as `mspt_avg_window`; it includes normal tick pacing and is not per-region execution latency. `scripts/lib/proc_sampler.sh:43–57` finds the newest process matching a pattern and tracks one quick-play client, not four owned clients. The plan correctly rejects average-MSPT-as-p99, but it does not yet supply the missing measure. Existing instrumentation “where available” cannot satisfy a mandatory p99 gate by itself.

Amendment: define a metric contract before reference runs: execution duration versus start-to-start tick delay, owning region, per-client frame distributions, eligible/useful throughput denominator, server and each client RSS with sampling interval, units, missing-sample behavior and minimum sample counts. Bind sampling to recorded process identities and lifetimes, not newest-name matches. Add bounded fixture instrumentation if needed, equally enabled on baseline and candidate. Specify whether budgets apply to each client/region or an aggregate (prefer per eligible subject plus aggregate), the run-level statistic and how three paired repetitions are judged. State the absolute-floor formula and freeze baseline/noise calibration before candidate evaluation. Reset disposable world/store/cache state for each repetition and pin replay/update/generation workload so later runs cannot become warm by accident. Put an explicit numerical frame-time budget or declared exploratory result behind P1/P5's current “no material regression” wording. If instrumentation or baseline stability is unavailable, presets must wait rather than borrowing aggregate counters as performance evidence.

## V4 — MEDIUM: The progress law needs an independent eligibility and completion oracle

Section: §11 P6b scenarios and correctness laws.

“Every eligible non-stalled client progresses in each 30-second window” can fail a correctly completed warm-cache client or pass a starving client if eligibility is inferred from the same stalled request manager. A single delivery every 30 seconds can also satisfy this law while permanently losing one edited target. The scenarios mention correct bodies/stamps but do not give that assertion a deadline or define the acquisition state of the finite target set.

Amendment: define each subject's run-scoped offered target set, expected cached/validated/body outcome, intentional throttle/fault interval and independent eligibility predicate. A completed subject exits the throughput denominator honestly; it is not counted as stalled. Track per-target edit/body or stamp expectations through churn with bounded completion/drain deadlines and old-session identifiers. Prove one-client starvation, one lost current edit and stale replacement-session delivery each fail the checker. Treat terminal outcomes permitted by configured generation policy separately from useful body throughput. Keep any slow-consumer behavior inside fixture code using existing APIs/hooks; do not turn it into excluded item 6.

## V5 — MEDIUM: P6a needs test execution-environment parity as well as dependency closure

Sections: §3 P0; §9 P6a; §14 all-line gates.

Evidence: `26.2/common/build.gradle` currently has no JUnit setup and uses compile-only fastutil/Gson/sqlite/zstd. `fabric/build.gradle` supplies test-only libraries, optional experiment properties, golden artifact inputs and task resource inputs. Many current source/contract tests resolve module-relative paths; e.g. `ChannelAccessorContractTest` opens `src/main/resources/lss.mixins.json` and `../paper/...`. These tests may look pure Java by imports while having a platform/repository working-directory contract. The plan already appropriately excludes MC stubs and demands inventory; this is an additional execution closure to inventory, not grounds to move all tests.

Amendment: include working directory, system properties, discovery engine, assumptions/skip reasons, test-task inputs and resource/classloader provenance in each move record. Preserve opt-in experimental tools as named opt-in rows, not mandatory “all tests” gates. Demonstrate a representative changed external resource invalidates the migrated test task's cache and reruns it. Define normalized test identities with parameter cases and owning task so moved classes cannot disappear or be counted twice. Wire `common:test` into documented required aggregate/CI paths and preserve Java 21 execution on all five lines. No speedup claim until matched warm/cold task-cache conditions have been measured.

## V6 — MEDIUM: Required supported profiles must be separated from optional integration gaps

Sections: §8 P4 acceptance; §14 final acceptance.

Evidence: `1.21.1/docs/testing/astra-live-profiles.md` distinguishes Xaero-only Fabric, modern Neo Connector Voxy and a dedicated Neo profile with a wrong-MC Voxy jar. The later `astra-implementation-ledger.md:184–188` records four actual UI combinations as green but explicitly rejects native Neo Voxy0.2.9-alpha acceptance without attributing its cause. Elytra observations are specifically 1.21.10 and 1.21.11. Renderer availability and shipping remain separate on 26.2 Neo.

The plan preserves these distinctions generally, but “unsupported combinations as explicit skips” and “unresolved optional-profile failures” can be read as permitting a required UI/lifecycle gate to become optional when its installed Voxy pairing fails. Conversely, demanding all named profiles green could force an out-of-scope third-party fix.

Amendment: before rig execution, enumerate concrete required rows (line, loader, Sodium generation, consumer mode, render capability, scenario), including Xaero-only rows where sufficient; spell out the two Elytra lines and two locked C2ME artifacts. Give separate outcomes for unsupported, applicable-but-blocked and rejected optional pairing. Required supported UI/receive/lifecycle rows cannot be skipped solely because an unrelated optional Voxy artifact fails. A rejected native pairing remains an honest optional gap unless the approved capability matrix explicitly makes it required. Final mandatory gates must run against the final relevant artifact/profile identity, while documentation-only follow-ups may reuse identity-matching evidence without redundant runtime launches.

## Confirmed boundaries to retain

The plan correctly keeps 1.21.1 Folia and Tier 3 out of execution, preserves 26.x Neo renderer stubs, distinguishes send-admission denial from physical Netty saturation, requires real visual review, retains first-failure evidence, protects normal profiles/worlds, measures rather than promises test speedup, and refuses to remove Folia's experimental label from a bounded scenario pass. Nothing in this review requires product protocol changes, a new public consumer API, targeted repair/backfill, additional platform support or publishing redesign.
