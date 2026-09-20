"""Validate a reviewed cross-line batch without allowing one line to waive another."""
import re
from pathlib import PurePosixPath
from catalog import require


def validate_batch(batch, baseline):
    require(batch.get('schema_version') == 1, 'unsupported batch schema')
    require(batch.get('baseline') == baseline, 'batch baseline differs from catalog snapshot')
    targets = batch.get('required_targets')
    require(isinstance(targets, list) and set(targets) == set(baseline)
            and len(targets) == len(set(targets)), 'batch must name each required support line once')
    reviewed = batch.get('reviewed_candidate_blobs')
    require(isinstance(reviewed, dict) and set(reviewed) == set(targets), 'batch needs reviewed blobs for every target')
    for line, paths in reviewed.items():
        require(isinstance(paths, dict), 'reviewed blobs must be a path mapping')
        for path, blob in paths.items():
            require(isinstance(path, str) and path and not PurePosixPath(path).is_absolute()
                    and '..' not in PurePosixPath(path).parts, 'invalid reviewed path')
            require(blob is None or isinstance(blob, str) and re.fullmatch('[0-9a-f]{40}', blob), 'invalid reviewed blob')
    edges = batch.get('prerequisites')
    require(isinstance(edges, dict) and set(edges) == set(targets), 'batch needs explicit prerequisite lists')
    visited = set()
    active = set()
    def visit(line):
        require(line not in active, 'cyclic batch prerequisites')
        if line in visited:
            return
        active.add(line)
        deps = edges[line]
        require(isinstance(deps, list) and len(deps) == len(set(deps)) and set(deps) <= set(targets), 'invalid prerequisite target')
        for dependency in deps:
            visit(dependency)
        active.remove(line)
        visited.add(line)
    for line in targets:
        visit(line)
    invariants = batch.get('final_shared_invariants')
    require(isinstance(invariants, list) and invariants, 'batch needs explicit final shared invariants')
    for path in invariants:
        require(all(path in reviewed[line] for line in targets), 'invariant missing a target blob')
        require(len({reviewed[line][path] for line in targets}) == 1 and reviewed[targets[0]][path] is not None,
                'shared invariant must have identical present final blobs')
    return batch


def apply_batch(report, batch, candidate_line, candidate_blobs):
    """Permit only exact candidate-line adaptations; batch completion is separate."""
    reviewed = batch['reviewed_candidate_blobs'][candidate_line]
    for issue in report['issues']:
        path = issue['path']
        if issue['result'] == 'unclassified':
            continue
        if issue['result'] == 'shared-divergence' and path not in batch['final_shared_invariants']:
            continue
        if issue.get('line', candidate_line) != candidate_line:
            continue
        if path in reviewed and candidate_blobs.get(path) == reviewed[path]:
            issue['result'] = 'reviewed-batch-outstanding'
    report['passed'] = all(issue['result'] == 'reviewed-batch-outstanding' for issue in report['issues'])
    # A single candidate report cannot establish every target's result, even
    # when it happens to have no outstanding differences against the baseline.
    report['batch_complete'] = False
    report['batch_completion_required'] = {
        'targets': batch['required_targets'],
        'final_shared_invariants': batch['final_shared_invariants'],
        'gate': 'run the full explicit-ref cross-line check after all target source snapshots are recorded',
    }
    return report
