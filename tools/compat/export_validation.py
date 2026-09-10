#!/usr/bin/env python3
"""Derive a line-local feature record from a collected owned run; never upload it."""
import argparse
import json
import sys
from datetime import datetime,timezone
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'rig'))
from rig import read,sha,regular,inside,alive
from proof import check_proof
from catalog import digest,validate_record


def export(run,candidate_target,fixture_targets,feature,evidence=(),limitations=()):
    run=Path(run).resolve();manifest=read(run/'manifest.json');runtime=read(run/'runtime.json')
    profile=read(run/'profile.json');scenario=read(run/'scenario.json');result=read(run/'evidence/result.json')
    for key in ('run_id','run_hash','profile_hash','scenario_hash'):
        if result.get(key)!=manifest.get(key):raise ValueError('collected run identity differs')
    if result.get('status') not in ('passed','failed','inconclusive'):raise ValueError('finished collected attempt required')
    if digest(runtime)!=manifest['runtime_hash'] or digest(profile)!=manifest['profile_hash'] or digest(scenario)!=manifest['scenario_hash']:
        raise ValueError('run inputs changed after collection')
    from review_state import ownership_errors
    ownership_failures=ownership_errors(run,runtime)
    if ownership_failures:raise ValueError('cannot establish complete owned cleanup: '+str(ownership_failures))
    if any(alive(owner) for owner in read(run/'processes.json')) or alive(read(run/'supervisor.json')):
        raise ValueError('owned run still active')
    passed=result['status']=='passed'
    if passed and result.get('cleanup')!='complete':raise ValueError('pass requires complete cleanup')
    proof=read(run/'proof.json') if (run/'proof.json').is_file() else {}
    if passed:
        errors=check_proof(proof,manifest,scenario,run)
        if errors:raise ValueError('collected proof no longer verifies: '+str(errors))
    staged={row['target']:row['sha256'] for row in manifest['run_manifest']['staged_inputs']}
    def artifact(path):
        if path not in staged or sha(regular(inside(run,path)))!=staged[path]:raise ValueError('selected artifact not in exact staged inputs')
        return staged[path]
    candidate=artifact(candidate_target)
    if candidate_target in fixture_targets or len(set(fixture_targets))!=len(fixture_targets):raise ValueError('invalid fixture selection')
    fixtures={path:artifact(path) for path in fixture_targets}
    world_inputs={path:value for path,value in staged.items() if path.endswith(('.mca','level.dat','.sqlite'))}
    if world_inputs and not runtime.get('world_digest'):raise ValueError('staged world requires explicit snapshot digest')
    config_hash=manifest['run_manifest']['generated_config_hash']
    world=runtime.get('world_digest') or digest({'kind':'fresh-generated-world','initial_configuration_hash':config_hash})
    memory={row['id']:[arg for arg in row['argv'] if arg.startswith(('-Xms','-Xmx','-XX:'))] for row in runtime['launches']}
    # Private launcher paths/account context and raw launcher logs are never exported.
    launcher_memory={}
    for path,value in runtime.get('generated_files',{}).items():
        if not path.endswith('instance.cfg') or not isinstance(value,str):continue
        for line in value.splitlines():
            key,separator,number=line.partition('=')
            if separator and key in ('MinMemAlloc','MaxMemAlloc') and number.isdigit():launcher_memory[key]=int(number)
    inputs={'candidate_sha256':candidate,'candidate_target':candidate_target,'fixture_sha256':digest(fixtures),'fixture_artifacts':fixtures,
            'checker_sha256':manifest['run_manifest']['checker_sha256'],'world_sha256':world,
            'world_input_kind':'frozen snapshot' if runtime.get('world_digest') else 'fresh generated world; initial seed/generator/configuration bound by digest',
            'effective_config':{'initial_generated_config_hash':config_hash},'jvm_flags':{'launch_memory_flags':memory,'prism_memory_mib':launcher_memory},
            'backend':manifest['backend'],'rig_run_hash':manifest['run_hash'],'runtime_hash':manifest['runtime_hash']}
    if scenario.get('execution_route') == 'native-server-smoke':
        from server_smoke_identity import export_binding
        inputs.update(export_binding(run,runtime,manifest,scenario,candidate_target,artifact))
    from scenario_checker_identity import closure
    scoped=closure(run/'tool-sources',scenario,runtime,Path(__file__).resolve().parents[2])
    if any(manifest['run_manifest']['runtime_tools'].get(name)!=value for name,value in scoped.items()):raise ValueError('retained scenario checker dependency changed')
    from candidate_identity import candidate_bindings
    inputs.update(scenario_version=scenario.get('version',1),scenario_hash=manifest['scenario_hash'],
                  runtime_tools_sha256=digest(manifest['run_manifest']['runtime_tools']),
                  scenario_checker_sha256=digest(scoped),scenario_checker_sources=scoped,
                  candidate_artifacts=candidate_bindings(runtime,artifact))
    if inputs['candidate_artifacts'].get(candidate_target)!=candidate:raise ValueError('selected candidate lacks native product metadata binding')
    participants={}
    for reference in manifest.get('participants',[]):
        role=reference['role'];locked=read(regular(inside(run,'participants/'+role+'.json')))
        if role in participants or locked['id']!=reference['id'] or digest(locked)!=reference['profile_hash']:raise ValueError('retained participant identity changed')
        participants[role]={'id':locked['id'],'profile_hash':digest(locked)}
    inputs['participant_profiles']=participants
    names={'evidence/result.json',*evidence}
    if (run/'proof.json').is_file():names.add('proof.json')
    declared=dict(proof.get('evidence',{}))
    declared.update(proof.get('source_seed_report',{}).get('artifacts',{}))
    report=proof.get('gametest_report',{})
    if report.get('artifact'):declared[report['artifact']]=report['artifact_sha256']
    for review in proof.get('reviews',{}).values():
        if review.get('artifact'):declared[review['artifact']]=review['artifact_sha256']
    for name,expected in declared.items():
        path=inside(run/'evidence',name)
        if sha(regular(path))!=expected:raise ValueError('proof evidence bytes changed')
        names.add('evidence/'+name)
    for name in names:
        if name!='proof.json' and not name.startswith('evidence/'):raise ValueError('export only explicitly selected run evidence')
        if name.endswith('.private.log') or 'launcher' in Path(name).name.lower():raise ValueError('raw launcher/private logs cannot be indexed for export')
    index={name:sha(regular(inside(run,name))) for name in sorted(names)}
    finished=manifest.get('finished_at')
    if not isinstance(finished,(float,int)) or isinstance(finished,bool):raise ValueError('actual attempt completion timestamp required')
    record={'schema_version':1,'run_id':manifest['run_id'],'timestamp':datetime.fromtimestamp(finished,timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
            'profile_id':profile['id'],'profile_hash':manifest['profile_hash'],'run_manifest':inputs,'run_hash':digest(inputs),
            'scenario':scenario['id'],'feature':feature,'result':{'passed':'pass','failed':'fail','inconclusive':'inconclusive'}[result['status']],
            'evidence_index':index,'evidence_sha256':digest(index),'limitations':list(limitations)}
    if result.get('cleanup')!='complete':record['limitations'].append('Cleanup incomplete in this failed/inconclusive attempt.')
    validate_record(record,{profile['id']:profile});return record


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('run');parser.add_argument('--candidate-target',required=True)
    parser.add_argument('--fixture-target',action='append',default=[]);parser.add_argument('--feature',required=True)
    parser.add_argument('--evidence',action='append',default=[]);parser.add_argument('--limitation',action='append',default=[]);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();record=export(args.run,args.candidate_target,args.fixture_target,args.feature,args.evidence,args.limitation)
    with args.output.open('x') as stream:json.dump(record,stream,indent=2,sort_keys=True);stream.write('\n')
    print(json.dumps({'run_id':record['run_id'],'result':record['result'],'output':str(args.output)}))
