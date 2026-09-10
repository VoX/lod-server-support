#!/usr/bin/env python3
"""Record fixed calibration and measured slots before their runtime is created."""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import re
import time
import performance

FIELDS=('world_digest','profile_hash','fixture_hash','hardware_hash','jvm_hash','workload_hash')
ORDER=('baseline','candidate','candidate','baseline','baseline','candidate')
def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def read(path):return json.loads(Path(path).read_text())
def create_file(path,value):
    with Path(path).open('x') as stream:json.dump(value,stream,indent=2,sort_keys=True);stream.write('\n')
def init(root,registration):
    if set(registration.get('arms',{}))!={'baseline','candidate'} or any(not registration.get(field) for field in FIELDS):
        raise ValueError('complete preregistered arms and shared input identities required')
    if registration['arms']['baseline']==registration['arms']['candidate']:raise ValueError('arms must differ')
    if any(not isinstance(registration[field],str) or re.fullmatch('[0-9a-f]{64}',registration[field]) is None for field in FIELDS):
        raise ValueError('shared inputs require full SHA256 identities')
    for identity in registration['arms'].values():
        if not isinstance(identity.get('source_tree'),str) or re.fullmatch('[0-9a-f]{40}',identity['source_tree']) is None:
            raise ValueError('arm requires a full Git source tree identity')
        hashes=identity.get('artifact_hashes')
        if not isinstance(hashes,dict) or not hashes or any(not isinstance(value,str) or re.fullmatch('[0-9a-f]{64}',value) is None for value in hashes.values()):
            raise ValueError('arm requires full artifact SHA256 identities')
    root.mkdir(parents=True,exist_ok=False)
    registration=dict(registration,schema_version=1,experiment_id=secrets.token_hex(16),created_ns=time.time_ns(),calibration_runs=3,measured_order=list(ORDER))
    create_file(root/'registration.json',registration)
    return registration

def intent(root,phase):
    registration=read(root/'registration.json')
    if phase not in ('calibration','measured'):raise ValueError('unknown experiment phase')
    if phase=='calibration' and (root/'frozen.json').exists():raise ValueError('calibration already frozen')
    if phase=='measured' and not (root/'frozen.json').exists():raise ValueError('calibration must be frozen first')
    limit=3 if phase=='calibration' else 6
    for slot in range(limit):
        if (root/f'{phase}-{slot}-result.json').exists():continue
        pending=root/f'{phase}-{slot}-intent.json'
        if pending.exists():return read(pending)
        value={'schema_version':1,'experiment_id':registration['experiment_id'],'registration_sha256':digest(registration),
               'phase':phase,'slot':slot,'arm':'baseline' if phase=='calibration' else ORDER[slot],
               'created_ns':time.time_ns(),'nonce':secrets.token_hex(16)}
        if phase=='measured':value['calibration_sha256']=digest(read(root/'frozen.json'))
        create_file(pending,value);return value
    raise ValueError('all fixed slots already recorded')

def record(root,report):
    binding=report.get('measurement_context',{});phase=binding.get('phase');slot=binding.get('slot')
    if phase not in ('calibration','measured') or not isinstance(slot,int) or isinstance(slot,bool):raise ValueError('missing runtime-bound measurement intent')
    pending=root/f'{phase}-{slot}-intent.json'
    if not pending.exists() or read(pending)!=binding:raise ValueError('runtime differs from preregistered intent')
    registration=read(root/'registration.json')
    if binding['registration_sha256']!=digest(registration):raise ValueError('registration changed after intent')
    if report.get('artifact_identity')!=registration['arms'][binding['arm']]:raise ValueError('arm artifact identity mismatch')
    for field in FIELDS:
        if report.get(field)!=registration[field]:raise ValueError('shared experiment input changed: '+field)
    if not report.get('run_id') or not report.get('run_hash'):raise ValueError('run identity missing')
    for existing in root.glob('*-result.json'):
        if read(existing)['report']['run_id']==report['run_id']:raise ValueError('run reused')
    # Record failures too. A failed selected run cannot be silently replaced.
    value={'report':report,'report_sha256':digest(report),'recorded_ns':time.time_ns()}
    create_file(root/f'{phase}-{slot}-result.json',value)
    return value

def freeze(root):
    registration=read(root/'registration.json');runs=[];hashes=[]
    for slot in range(3):
        value=read(root/f'calibration-{slot}-result.json')
        if digest(value['report'])!=value['report_sha256']:raise ValueError('calibration report changed')
        runs.append(value['report']);hashes.append(value['report_sha256'])
    floors=performance.calibrate(runs)
    result={'schema_version':1,'registration_sha256':digest(registration),'calibration_report_hashes':hashes,
            'absolute_floors':floors,'frozen_ns':time.time_ns()}
    create_file(root/'frozen.json',result);return result

def evaluate(root):
    registration=read(root/'registration.json');frozen=read(root/'frozen.json')
    if frozen['registration_sha256']!=digest(registration):raise ValueError('registration changed after calibration')
    calibration=[read(root/f'calibration-{slot}-result.json') for slot in range(3)]
    if [digest(value['report']) for value in calibration]!=frozen['calibration_report_hashes']:
        raise ValueError('frozen calibration evidence changed')
    if performance.calibrate([value['report'] for value in calibration])!=frozen['absolute_floors']:
        raise ValueError('absolute floors changed after calibration')
    runs=[]
    for slot,arm in enumerate(ORDER):
        value=read(root/f'measured-{slot}-result.json');report=value['report'];binding=report['measurement_context']
        if value['report_sha256']!=digest(report) or binding!=read(root/f'measured-{slot}-intent.json'):
            raise ValueError('measured report/intent changed')
        if binding.get('calibration_sha256')!=digest(frozen) or binding['arm']!=arm:
            raise ValueError('measurement preceded frozen calibration or order changed')
        runs.append(report)
    experiment={**registration,'preregistered':True,'calibration_frozen_before_candidate':True,'absolute_floors':frozen['absolute_floors'],'pairs':[]}
    for index in range(3):
        order=list(ORDER[index*2:index*2+2]);experiment['pairs'].append({'order':order,order[0]:runs[index*2],order[1]:runs[index*2+1]})
    return {'experiment':experiment,'result':performance.evaluate(experiment)}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['init','intent','record','freeze','evaluate']);parser.add_argument('directory',type=Path);parser.add_argument('--input',type=Path);parser.add_argument('--phase',choices=['calibration','measured']);args=parser.parse_args()
    if args.command=='init':result=init(args.directory,read(args.input))
    elif args.command=='intent':result=intent(args.directory,args.phase)
    elif args.command=='record':result=record(args.directory,read(args.input))
    elif args.command=='freeze':result=freeze(args.directory)
    else:result=evaluate(args.directory)
    print(json.dumps(result,indent=2,sort_keys=True))
