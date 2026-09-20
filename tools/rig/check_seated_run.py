"""Bind seated draw semantics to the actual owned observer log and immutable inputs."""
import re
import hashlib,uuid
from pathlib import Path
from check_seated_draw import check

def inspect(run,manifest):
    from rig import read,inside,regular,digest
    run=Path(run);runtime=read(run/'runtime.json');scenario=read(run/'scenario.json')
    if digest(runtime)!=manifest['runtime_hash'] or digest(scenario)!=manifest['scenario_hash']:
        raise ValueError('changed seated-draw inputs')
    if scenario.get('checker')!='seated-draw':raise ValueError('seated-draw checker not selected')
    path=regular(inside(run,'instances/lss-rig-client/minecraft/logs/latest.log'))
    if path.stat().st_size>32*1024*1024:raise ValueError('observer log bound exceeded')
    text=path.read_text(errors='replace')
    rows=[line[line.index('[WI9-FIXTURE]'):] for line in text.splitlines() if '[WI9-FIXTURE]' in line]
    result=check('\n'.join(rows))
    subjects={}
    launches={row.get('id'):row for row in runtime.get('launches',[])}
    for suffix,name in [('a','SeatedSubjectA'),('b','SeatedSubjectB')]:
        role='seated-target-'+suffix
        native=regular(inside(run,role+'/logs/latest.log'))
        if native.stat().st_size>32*1024*1024:raise ValueError('subject log bound exceeded')
        body=native.read_bytes();native_text=body.decode(errors='replace')
        argv=launches.get(role,{}).get('argv',[])
        user=argv[argv.index('--username')+1] if '--username' in argv and argv.index('--username')+1<len(argv) else None
        identity=str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3))
        enabled=bool(re.search(r'Server session config received \(protocol v20, LOD distance: \d+ chunks, enabled: true\)',native_text))
        consumer='LSS_SEATED_TARGET_CONSUMER registered=true' in native_text
        if user!=name or '-Dlss.rig.seatedTarget=true' not in argv:result['errors'].append('subject native launch identity differs: '+role)
        if not enabled or not consumer:result['errors'].append('subject actual consumer/negotiation absent: '+role)
        observed=[line for line in native_text.splitlines() if 'LSS_SEATED_TARGET_CONSUMER' in line or 'Server session config received' in line]
        subjects[role]=dict(name=name,uuid=identity,handshake=enabled,consumer_registered=consumer,observations=observed,observations_sha256=digest(observed))
    armed=next((row for row in rows if '[WI9-FIXTURE] ARMED ' in row),'')
    fields=dict(word.split('=',1)for word in armed.split()if '='in word)
    if {fields.get('first_seated'),fields.get('second')}!={row['uuid']for row in subjects.values()}:
        result['errors'].append('native proxies differ from the two exact owned subjects')
    handshake=bool(re.search(r'Server session config received \(protocol v20, LOD distance: \d+ chunks, enabled: true\)',text))
    if not handshake:result['errors'].append('actual enabled v20 negotiation absent')
    if runtime.get('seated_capture_version')==2:
        from rig import sha
        if not any('[WI9-FIXTURE] HEALTHY_READY ' in row for row in rows):
            result['errors'].append('healthy scoped native draw premise absent')
        captures=read(regular(inside(run,'evidence/seated-captures.json')))
        if any(captures.get(key)!=manifest[key] for key in ('run_id','run_hash')):
            result['errors'].append('seated native captures belong to another run')
        for phase in ('healthy','recovery'):
            declared=captures.get(phase,{})
            expected='seated-'+phase+'.png'
            if declared.get('artifact')!=expected or declared.get('artifact_sha256')!=sha(regular(inside(run,'evidence/'+expected))):
                result['errors'].append('seated native capture changed: '+phase)
        gate=regular(inside(run,'evidence/seated-healthy-captured.txt'))
        if gate.read_text()!=manifest['run_id']+'\n' or sha(gate)!=captures.get('gate_sha256'):
            result['errors'].append('healthy native capture gate differs')
        result['captures']=captures
    result['passed']=not result['errors']
    result['assertions']={name:result['passed'] for name in result['assertions']}
    return dict(result,run_id=manifest['run_id'],run_hash=manifest['run_hash'],handshake=handshake,
                observations=rows,observations_sha256=digest(rows),subjects=subjects)

def make_proof(run,manifest,review_artifacts=None):
    from rig import write,read,sha
    run=Path(run);report=inspect(run,manifest)
    path=run/'evidence/seated-draw.json';write(path,report)
    # Human review is independent and must retain its own exact artifact/run binding.
    prior=read(run/'proof.json') if (run/'proof.json').exists() else {}
    prior_failures=prior.get('failures',[])
    if not isinstance(prior_failures,list):prior_failures=['invalid prior fixture failure declaration'] if prior_failures else []
    failures=list(dict.fromkeys([str(value) for value in prior_failures]+report['errors']))
    proof={key:manifest[key] for key in ('run_id','run_hash','profile_hash','scenario_hash')}
    proof.update(ready=report['handshake'],handshake=report['handshake'],test_count=5 if report['passed'] else 0,
                 assertions=report['assertions'],failures=failures,seated_report=report,
                 evidence={'seated-draw.json':sha(path)},reviews=prior.get('reviews',{}),
                 review_artifacts=prior.get('review_artifacts',{}) if review_artifacts is None else review_artifacts)
    write(run/'proof.json',proof)
    return proof

def check_report(proof,manifest,root):
    try:
        from rig import sha,regular,inside,read
        path=regular(inside(Path(root)/'evidence','seated-draw.json'))
        if sha(path)!=proof.get('evidence',{}).get('seated-draw.json'):return ['seated report evidence changed or missing']
        actual=inspect(root,manifest)
        if read(path)!=actual:return ['seated retained report differs from native evidence']
        if proof.get('seated_report')!=actual:return ['seated raw report changed or missing']
        if 'captures' in actual and proof.get('review_artifacts',{}).get('visual_render')!=actual['captures']['healthy']:
            return ['human review must bind the healthy pre-fault native capture']
        return actual['errors']
    except (ValueError,OSError,KeyError,TypeError) as error:return ['seated report invalid: '+str(error)]
