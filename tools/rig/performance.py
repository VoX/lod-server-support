#!/usr/bin/env python3
"""Fixed three-pair performance protocol and independent target-oracle correctness.
Durations are milliseconds, throughput useful bytes/second, RSS bytes. Missing
samples are inconclusive. Input is fixture evidence, never game log heuristics.
"""
import argparse
import json
import math
import statistics
from pathlib import Path

DURATION_METRICS = ('tick_execution_p95_ms', 'tick_execution_p99_ms', 'tick_delay_p95_ms', 'tick_delay_p99_ms', 'frame_p95_ms', 'frame_p99_ms')
METRICS = DURATION_METRICS + ('useful_bytes_per_second', 'peak_rss_bytes')

def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0

def overlap(samples):
    # Platform fixture supplies genuine owning-region callback intervals, with a
    # per-lifetime identity, not OS threads or asynchronous/global work.
    valid = [s for s in samples if s.get('owns_region') is True and s.get('context') == 'owning-region' and s['end_ns'] > s['start_ns']]
    latest = {}
    for sample in sorted(valid, key=lambda row: row['start_ns']):
        key = sample['region_identity'], sample['subject']
        if any(region != key[0] and subject != key[1] and end > sample['start_ns']
               for (region, subject), end in latest.items()):
            return True
        latest = {k: end for k, end in latest.items() if end > sample['start_ns']}
        latest[key] = max(latest.get(key, 0), sample['end_ns'])
    return False

def correctness(run):
    errors = []
    if run.get('platform') == 'folia' and not overlap(run.get('region_samples', [])):
        errors.append('owning-region overlap absent')
    identities = run.get('sessions', [])
    if len({s['subject'] for s in identities}) < run.get('required_subjects', 4):
        errors.append('independent simultaneous subjects missing')
    active = set()
    maximum = 0
    events = [(s['start_ns'], 1, s['subject'], s['connection_id']) for s in identities]
    events += [(s['end_ns'], 0, s['subject'], s['connection_id']) for s in identities]
    for timestamp, entering, subject, connection in sorted(events):
        if entering:
            if any(current_subject == subject for current_subject, _ in active):
                errors.append('same subject has overlapping connection identities')
            active.add((subject, connection))
        else:
            active.discard((subject, connection))
        maximum = max(maximum, len({s for s, _ in active}))
    if maximum < run.get('required_subjects', 4):
        errors.append('sessions never simultaneously registered')
    if any(s['end_ns'] <= s['start_ns'] for s in identities):
        errors.append('invalid session interval')
    if len({s['connection_id'] for s in identities}) != len(identities):
        errors.append('connection identity reused')
    oracle = run.get('oracle', [])
    if not oracle:
        errors.append('independent target oracle missing')
    for target in oracle:
        if target.get('actual') != target.get('expected') or target.get('actual') is None:
            errors.append('lost/current update: ' + target['id'])
        if target.get('delivery_session') != target.get('expected_session'):
            errors.append('stale-session delivery: ' + target['id'])
        if target.get('resolved_ns', 0) - target['fault_removed_ns'] > 120_000_000_000:
            errors.append('edit exceeded recovery deadline: ' + target['id'])
    windows = run.get('progress_windows', [])
    start_ns, end_ns = run.get('measurement_start_ns'), run.get('measurement_end_ns')
    subject_ids = {row.get('subject') for row in identities}
    if not isinstance(start_ns, int) or not isinstance(end_ns, int) or end_ns <= start_ns:
        errors.append('measurement window bounds missing')
    else:
        expected_windows = {(subject, start) for subject in subject_ids
                            for start in range(start_ns, end_ns - 29_999_999_999, 30_000_000_000)}
        reported = {(row.get('subject'), row.get('start_ns')) for row in windows}
        if expected_windows != reported or len(reported) != len(windows):
            errors.append('oracle observation windows omitted/duplicated')
    if not windows:
        errors.append('oracle eligibility/progress windows missing')
    for window in windows:
        subject, start = window['subject'], window.get('start_ns', 0)
        end = start + 30_000_000_000
        targets = [target for target in oracle if target.get('subject') == subject]
        eligible = any(target.get('offered_ns', 0) < end and target.get('resolved_ns', math.inf) > start for target in targets)
        # Controlled stalls originate in the preregistered fault oracle, not a
        # request-manager silence or a self-declared window eligibility bit.
        stalled = any(fault.get('subject') == subject and fault.get('kind') == 'slow-consumer'
                      and fault['start_ns'] <= start and fault['end_ns'] >= end for fault in run.get('faults', []))
        if window.get('eligible') != eligible:
            errors.append('window eligibility disagrees with independent target oracle')
        useful = sum(start <= target.get('resolved_ns', -1) < end
                     and target.get('actual') is not None and target.get('actual') == target.get('expected')
                     and target.get('delivery_session') == target.get('expected_session') for target in targets)
        if window.get('useful_outcomes') != useful:
            errors.append('window progress disagrees with independent target outcomes')
        if (eligible or window.get('eligible')) and not stalled and window.get('useful_outcomes', 0) <= 0:
            errors.append('starvation: ' + subject)
        if window.get('duration_seconds') != 30:
            errors.append('invalid progress window duration')
    if run.get('queue_bounds_ok') is not True:
        errors.append('queue bounds unproven')
    if run.get('cleanup_complete') is not True or run.get('drain_seconds', math.inf) > 120:
        errors.append('cleanup incomplete')
    return errors

def sample_errors(run):
    errors = []
    subjects = run.get('metrics', {})
    if 'aggregate' not in subjects or not subjects:
        errors.append('aggregate metrics missing')
    expected_subjects = {'server', 'aggregate', *[row['subject'] for row in run.get('sessions', [])]}
    if set(subjects) != expected_subjects:
        errors.append('per-subject metrics omitted or unknown')
    if run.get('warmup_seconds') != 120 or run.get('measurement_end_ns', 0) - run.get('measurement_start_ns', 0) != 600_000_000_000:
        errors.append('fixed warmup/measurement duration missing')
    for name, sample in subjects.items():
        errors.extend(name + ': raw observation error: ' + str(error) for error in sample.get('errors', []))
        if (name in ('server', 'aggregate') and min(sample.get('tick_samples', 0), sample.get('tick_delay_samples', 0)) < 1000) or (name != 'server' and sample.get('frame_samples', 0) < 1000):
            errors.append(name + ': insufficient duration samples')
        if sample.get('rss_scheduled', 0) <= 0 or sample.get('rss_observed', 0) / sample['rss_scheduled'] < .95:
            errors.append(name + ': insufficient RSS coverage')
        if not sample.get('process_identity'):
            errors.append(name + ': process creation identity missing')
        for metric in METRICS:
            if (metric.startswith('frame_') and name == 'server') or (metric.startswith('tick_') and name not in ('server', 'aggregate')):
                continue
            if not finite(sample.get(metric)):
                errors.append(name + ': missing metric ' + metric)
    return errors

def calibrate(baselines):
    if len(baselines) < 3:
        raise ValueError('at least three separate baseline calibration runs required')
    floors = {}
    for run in baselines:
        if sample_errors(run) or correctness(run):
            raise ValueError('inconclusive calibration samples/correctness')
    subjects = set(baselines[0]['metrics'])
    if any(set(run['metrics']) != subjects for run in baselines):
        raise ValueError('calibration subjects differ')
    for subject in subjects:
        floors[subject] = {}
        for metric in METRICS:
            if (metric.startswith('frame_') and subject == 'server') or (metric.startswith('tick_') and subject not in ('server', 'aggregate')):
                continue
            values = [run['metrics'][subject][metric] for run in baselines]
            floor = max(abs(a - b) for a in values for b in values)
            if floor > .1 * statistics.median(values):
                raise ValueError('unstable baseline exceeds preregistered relative budget')
            floors[subject][metric] = floor
    return floors

def evaluate(experiment):
    pairs = experiment.get('pairs', [])
    if len(pairs) != 3 or not experiment.get('preregistered') or not experiment.get('calibration_frozen_before_candidate'):
        return {'status': 'inconclusive', 'errors': ['three preregistered pairs and frozen calibration required']}
    expected_order = [['baseline', 'candidate'], ['candidate', 'baseline'], ['baseline', 'candidate']]
    if [pair.get('order') for pair in pairs] != expected_order:
        return {'status': 'inconclusive', 'errors': ['paired order must alternate']}
    errors, missing = [], []
    arms=experiment.get('arms',{})
    for arm in ('baseline','candidate'):
        identity=arms.get(arm,{})
        if not identity.get('source_tree') or not identity.get('artifact_hashes'):
            missing.append('missing preregistered source/artifact identity: '+arm)
    if arms.get('baseline')==arms.get('candidate'):
        missing.append('baseline and candidate source/artifacts are identical')
    seen = set()
    for pair in pairs:
        for arm in ('baseline', 'candidate'):
            run = pair[arm]
            if run.get('artifact_identity')!=arms.get(arm) or not run.get('artifact_identity'):
                missing.append('measured artifact identity disagrees with arm: '+arm)
            if run.get('run_id') in seen or not run.get('run_id'):
                missing.append('duplicate/missing measured run identity')
            seen.add(run.get('run_id'))
            errors.extend(correctness(run))
            missing.extend(sample_errors(run))
            for field in ('world_digest', 'profile_hash', 'fixture_hash', 'hardware_hash', 'jvm_hash', 'workload_hash'):
                if not run.get(field) or run[field] != experiment.get(field):
                    missing.append('unmatched measurement identity: ' + field)
    if errors:
        return {'status': 'failed', 'errors': errors}
    if missing:
        return {'status': 'inconclusive', 'errors': missing}
    subjects = set(pairs[0]['baseline']['metrics'])
    if any(set(pair[arm]['metrics']) != subjects for pair in pairs for arm in ('baseline', 'candidate')):
        return {'status': 'inconclusive', 'errors': ['metric subjects differ']}
    comparisons = []
    for subject in sorted(subjects):
        for metric in METRICS:
            if (metric.startswith('frame_') and subject == 'server') or (metric.startswith('tick_') and subject not in ('server', 'aggregate')):
                continue
            floor = experiment.get('absolute_floors', {}).get(subject, {}).get(metric)
            if not finite(floor):
                return {'status': 'inconclusive', 'errors': ['unfrozen/missing absolute floor']}
            changes, bounds = [], []
            for pair in pairs:
                baseline = pair['baseline']['metrics'][subject][metric]
                candidate = pair['candidate']['metrics'][subject][metric]
                changes.append(baseline - candidate if metric == 'useful_bytes_per_second' else candidate - baseline)
                bounds.append(max(.1 * baseline, floor))
            passed = statistics.median(changes) <= statistics.median(bounds) and sum(d <= b for d, b in zip(changes, bounds)) >= 2
            comparisons.append({'subject': subject, 'metric': metric, 'paired_differences': changes, 'bounds': bounds, 'passed': passed})
    return {'status': 'passed' if all(c['passed'] for c in comparisons) else 'failed', 'comparisons': comparisons}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('experiment')
    args = parser.parse_args()
    result = evaluate(json.loads(Path(args.experiment).read_text()))
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['status'] == 'passed' else 1)
