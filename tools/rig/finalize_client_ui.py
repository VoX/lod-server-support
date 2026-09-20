#!/usr/bin/env python3
"""Finalize seven UI assertions after actual operator inspection of the raw captures."""
import argparse,json
from pathlib import Path
from rig import read,write,regular,sha,inside
from check_client_ui import check_report,ASSERTIONS,SCREENS

def finalize(root,visuals_checked):
    root=Path(root).resolve()
    if not visuals_checked:raise ValueError('actual screenshot inspection must precede finalization')
    if (root/'proof.json').exists():raise ValueError('refusing to replace an existing attempt proof')
    manifest,profile,runtime,scenario=[read(root/(name+'.json'))for name in ('manifest','profile','runtime','scenario')]
    if manifest.get('status')!='running':raise ValueError('only the active owned UI attempt can be finalized')
    config=inside(root,'instances/lss-rig-client/minecraft/config/lss-client-config.json')
    baseline=regular(root/'evidence/canonical-restored-baseline.json').read_bytes()
    if regular(config).read_bytes()!=baseline or config.with_name(config.name+'.tmp').exists():raise ValueError('actual settings or save-failure blocker not restored')
    target='instances/lss-rig-client/minecraft/mods/lod-server-support-'+profile['platform']+'.jar'
    candidates=[row for row in runtime['stage_files']if row['target']==target]
    if len(candidates)!=1 or sha(regular(inside(root,target)))!=candidates[0]['sha256']:raise ValueError('actual candidate differs from owned runtime')
    # No user review is fabricated: these UI assertions permit operator inspection.
    # Map/far scenarios retain their separate required real-user review mechanism.
    notes=dict(run_hash=manifest['run_hash'],operator_visual_checked=True,
               candidate_sha256=candidates[0]['sha256'],candidate_target=target,
               screenshots={name:sha(regular(root/'evidence'/name))for name in SCREENS},
               limitations=['Seven client UI assertions only; no map, lifecycle, far-player rendering or performance claim.']+runtime.get('ui_limitations',[]))
    write(root/'evidence/ui-actions.json',notes)
    evidence={p.name:sha(regular(p))for p in sorted((root/'evidence').iterdir())if p.suffix in ('.png','.json')and p.name!='result.json'}
    proof={key:manifest[key]for key in ('run_id','run_hash','profile_hash','scenario_hash')}
    proof.update(ready=True,handshake=scenario.get('execution_route')!='client-ui-no-consumer',test_count=7,
                 assertions=dict.fromkeys(ASSERTIONS,True),failures=[],evidence=evidence,limitations=notes['limitations'])
    errors=check_report(proof,manifest,scenario,root)
    if errors:raise ValueError('; '.join(errors))
    # Exclusive write prevents accidentally rewriting even a concurrently arrived proof.
    with (root/'proof.json').open('x')as stream:json.dump(proof,stream,indent=2);stream.write('\n')
    return dict(run_id=manifest['run_id'],run_hash=manifest['run_hash'],evidence_files=len(evidence))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('root',type=Path);parser.add_argument('--visuals-checked',action='store_true',required=True);args=parser.parse_args()
    print(json.dumps(finalize(args.root,args.visuals_checked)))
