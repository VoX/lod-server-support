#!/usr/bin/env python3
"""Freeze regular files from a completed, explicitly checked disposable seed run."""
import argparse
import json
from pathlib import Path
import shutil
from rig import alive,digest,inside,read,regular,sha,write


def freeze(run,destination,world_names):
    run=Path(run).resolve();destination=Path(destination)
    manifest=read(run/'manifest.json');proof=read(run/'proof.json');result=read(run/'evidence/result.json')
    from proof import check_proof
    if read(run/'scenario.json').get('execution_route')!='source-seed' or check_proof(proof,manifest,read(run/'scenario.json'),run) or proof.get('assertions',{}).get('seed_world_ready') is not True:
        raise ValueError('completed seed-world proof required')
    if result.get('status')!='passed' or result.get('cleanup')!='complete' or result.get('run_hash')!=manifest['run_hash']:
        raise ValueError('seed run collection/cleanup incomplete')
    if alive(read(run/'supervisor.json')) or any(alive(p) for p in read(run/'processes.json')):
        raise ValueError('seed owner remains alive')
    if not world_names or len(set(world_names))!=len(world_names):raise ValueError('explicit unique world directories required')
    entries=[]
    for name in world_names:
        directory=inside(run/'server',name)
        if not directory.is_dir():raise ValueError('seed world absent')
        for path in sorted(directory.rglob('*')):
            if path.is_symlink():raise ValueError('symlink in seed world')
            if path.is_file():
                # Lock files are process state, not world/store/cache content.
                if path.name=='session.lock':continue
                entries.append({'file':str(path.relative_to(run/'server')),'sha256':sha(regular(path))})
    if not entries:raise ValueError('empty seed snapshot')
    destination.mkdir(mode=0o700,parents=True,exist_ok=False)
    for entry in entries:
        target=inside(destination,entry['file']);target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(run/'server'/entry['file'],target)
        if sha(target)!=entry['sha256']:raise ValueError('seed bytes changed during snapshot')
    record={'schema_version':1,'seed_run_hash':manifest['run_hash'],'files':entries,'world_digest':digest(entries)}
    write(destination/'snapshot.json',record)
    return record


def stages(snapshot):
    snapshot=Path(snapshot);record=read(snapshot/'snapshot.json')
    if digest(record['files'])!=record['world_digest']:raise ValueError('snapshot manifest digest changed')
    expected={entry['file'] for entry in record['files']}
    actual={str(path.relative_to(snapshot)) for path in snapshot.rglob('*') if path.is_file() and path!=snapshot/'snapshot.json'}
    if actual!=expected:raise ValueError('snapshot file set changed')
    result=[]
    for entry in record['files']:
        source=regular(inside(snapshot,entry['file']))
        if sha(source)!=entry['sha256']:raise ValueError('snapshot world bytes changed')
        result.append({'source':str(source.resolve()),'target':'server/'+entry['file'],'sha256':entry['sha256']})
    return record['world_digest'],result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('run');p.add_argument('destination');p.add_argument('--world',action='append',required=True)
    a=p.parse_args();print(json.dumps(freeze(a.run,a.destination,a.world),indent=2))
