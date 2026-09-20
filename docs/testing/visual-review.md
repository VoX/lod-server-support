# Reviewing a completed visual run

A visual scenario can finish as `awaiting-review` only when every semantic check passed and each missing human review already names a retained, hashed artifact. The runner stops its owned clients, server and display before returning. This state is not acceptance and cannot be exported as a passing compatibility result.

Inspect the declared artifacts under the run's evidence directory and request the user's assessment. Record only their actual response in `proof.json`'s `reviews` field. Each accepted review must contain `disposition: accepted`, the original `run_id`, `profile_hash` and `run_hash`, `reviewer_kind: user`, the user's reviewer identity, `review_source: {kind: user-message, reference: <actual response reference>}`, and the exact `artifact`/`artifact_sha256` already declared by that scenario. No other proof field may change after cleanup. A malformed or rejected review, missing image, changed image, changed semantic proof or runtime failure cannot become a pass through this route.

After recording the actual user response, explicitly finalize:

```bash
python3 /exact/run/tool-sources/tools/rig/rig.py review /exact/run
```

Use the run's retained, hash-verified original tools for a delayed review; current development tools may have advanced in the meantime. This executes the same collection checks, including dead owned processes, frozen runtime/config/artifact/tool identities and the native scenario checker. A normal `collect` still reports `awaiting-review` until the explicit `review` step succeeds. Failed runtime attempts cannot use this transition. Keep the final review/result with the original run; do not repurpose another run's screenshot or user answer.

Synthetic harness tests exercise this state machine using explicitly labeled fake fixtures. Their simulated user records and images are never real visual acceptance evidence.
