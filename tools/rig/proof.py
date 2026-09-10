"""Fail-closed scenario acceptance; process exit and screenshots are not semantic proof."""

def check_proof(proof, manifest, scenario, root=None):
    if not isinstance(proof,dict):return ['invalid semantic proof object']
    from review_state import check_frozen
    errors = check_frozen(proof, manifest)
    for field in ('run_id', 'profile_hash', 'scenario_hash', 'run_hash'):
        if proof.get(field) != manifest.get(field) or not proof.get(field):
            errors.append('missing/stale identity: ' + field)
    if proof.get('ready') is not True:
        errors.append('Minecraft readiness missing')
    requires_handshake = scenario.get('requires_handshake', True)
    if type(requires_handshake) is not bool:
        errors.append('invalid handshake applicability')
    elif requires_handshake:
        if proof.get('handshake') is not True:
            errors.append('actual handshake missing')
    elif scenario.get("execution_route") == "client-ui-no-consumer":
        from check_client_ui import check_report
        errors.extend(check_report(proof, manifest, scenario, root))
    elif scenario.get("execution_route") == "source-prefill":
        from check_prefill import check_report
        errors.extend(check_report(proof,manifest,root))
    elif scenario.get("execution_route") == "source-seed":
        from check_source_seed import check_report
        errors.extend(check_report(proof, manifest, scenario, root))
    else:
        errors.extend(check_server_gametest_report(proof, scenario, root))
    if scenario.get('execution_route') == 'native-server-smoke':
        from check_server_smoke_report import check_report as check_native_server_smoke
        if root is None:
            errors.append('native server smoke needs retained run evidence')
        else:
            errors.extend(check_native_server_smoke(proof, manifest, scenario, root))
    count = proof.get('test_count', 0)
    if type(count) is not int or count < scenario['required_test_count']:
        errors.append('required test count not reached')
    observed = proof.get('assertions', {})
    if not isinstance(observed,dict):
        errors.append('invalid semantic assertions')
        observed={}
    for requirement in scenario['assertions']:
        if observed.get(requirement) is not True:
            errors.append('missing semantic proof: ' + requirement)
    for requirement in scenario.get('human_reviews', []):
        reviews = proof.get('reviews', {})
        review = reviews.get(requirement, {}) if isinstance(reviews, dict) else {}
        if not isinstance(review, dict): review = {}
        valid=(review.get('disposition') == 'accepted' and review.get('run_id') == manifest.get('run_id') and review.get('profile_hash') == manifest.get('profile_hash')
               and review.get('run_hash') == manifest.get('run_hash') and review.get('reviewer_kind') == 'user'
               and isinstance(review.get('reviewer_id'),str) and bool(review['reviewer_id'].strip()))
        provenance=review.get('review_source',{})
        valid=valid and isinstance(provenance,dict) and provenance.get('kind')=='user-message' and isinstance(provenance.get('reference'),str) and bool(provenance['reference'].strip())
        try:
            from pathlib import Path
            from rig import inside,regular,sha
            name=review.get('artifact')
            valid=valid and root is not None and isinstance(name,str) and not Path(name).is_absolute()
            valid=valid and sha(regular(inside(Path(root)/'evidence',name)))==review.get('artifact_sha256')
            if 'review_artifacts' in proof:
                artifacts=proof['review_artifacts']
                declared=artifacts.get(requirement) if isinstance(artifacts,dict) else None
                valid=valid and declared=={'artifact':name,'artifact_sha256':review.get('artifact_sha256')}
        except (ValueError,OSError,TypeError):
            valid=False
        if not valid:
            errors.append('visual review pending/rejected: ' + requirement)
    if scenario.get('id') == 'ui-apply' or scenario.get('checker') == 'client-ui' or scenario.get('execution_route') == 'client-ui':
        from check_client_ui import check_report
        errors.extend(check_report(proof,manifest,scenario,root))
    if scenario.get('checker') == 'elytra':
        from check_elytra_run import check_report
        errors.extend(check_report(proof,manifest,scenario,root))
    if scenario.get('checker') == 'concurrent-sources':
        from check_source_run import check_report
        errors.extend(check_report(proof,manifest,root))
    if scenario.get('checker') == 'receive-lifecycle':
        from check_receive_run import check_report
        errors.extend(check_report(proof,manifest,scenario,root))
    if scenario.get('checker') == 'send-admission':
        from check_send_admission_run import check_report
        errors.extend(check_report(proof,manifest,root))
    if scenario.get('checker') == 'xaero-map':
        from check_xaero_map_run import check_report
        errors.extend(check_report(proof,manifest,root))
    if scenario.get('checker') == 'seated-draw':
        from check_seated_run import check_report
        errors.extend(check_report(proof,manifest,root))
    if root is not None and 'evidence' in proof:
        try:
            from pathlib import Path
            from rig import inside,regular,sha
            declared=proof['evidence']
            if not isinstance(declared,dict):raise ValueError('invalid evidence index')
            for name,expected in declared.items():
                if not isinstance(name,str) or Path(name).is_absolute():raise ValueError('invalid evidence path')
                if sha(regular(inside(Path(root)/'evidence',name)))!=expected:raise ValueError('changed evidence: '+name)
        except (ValueError,OSError,TypeError)as error:
            errors.append('declared proof evidence invalid: '+str(error))
    if proof.get('failures'):
        errors.append('fixture reported failures')
    return errors


def check_server_gametest_report(proof, scenario, root):
    """The server-only exception requires an exact successful native XML report."""
    from pathlib import Path
    import xml.etree.ElementTree as ET
    from rig import inside, regular, sha
    if scenario.get('execution_route') != 'server-gametest':
        return ['handshake exemption requires server-gametest route']
    required = scenario.get('required_gametests')
    if (not isinstance(required, list) or len(required) < scenario['required_test_count']
            or any(not isinstance(name, str) or not name for name in required)
            or len(set(required)) != len(required)):
        return ['server-gametest names missing/invalid']
    try:
        report = proof.get('gametest_report', {})
        name = report.get('artifact')
        if root is None or not isinstance(name, str) or Path(name).is_absolute():
            raise ValueError('report must be run evidence')
        path = regular(inside(Path(root) / 'evidence', name))
        if sha(path) != report.get('artifact_sha256'):
            raise ValueError('report digest mismatch')
        cases = ET.parse(path).getroot().findall('.//testcase')
        names = [case.get('name', '').lower() for case in cases]
        if not cases or len(names) != len(set(names)):
            raise ValueError('absent/duplicate test cases')
        if any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped')):
            raise ValueError('report contains unsuccessful test cases')
        if any(name.lower() not in names for name in required):
            raise ValueError('required named gametest absent')
        if type(proof.get('test_count')) is not int or proof['test_count'] != len(cases):
            raise ValueError('count differs from actual XML')
    except (ValueError, OSError, TypeError, AttributeError, ET.ParseError) as error:
        return ['server-gametest report invalid: ' + str(error)]
    return []
