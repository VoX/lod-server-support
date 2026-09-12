"""Recompute numeric-preset evidence; observed wire logs and typed/filesystem bytes are authoritative."""
import hashlib
import json
from pathlib import Path
import re
import stat

KEYS=('lodDistanceChunks','generationConcurrencyLimitGlobal','generationConcurrencyLimitPerPlayer')
SESSION=re.compile(rb'Server session config received \(protocol v(\d+), LOD distance: (\d+) chunks, enabled: (true|false)\)')
LABELS=('preview','applied','unrelated','undone','failure-preview','failed-save','retry-preview','retry-saved','empty-undo','restored')

def target_values(value):
    if set(value)!=set(KEYS) or any(type(value[k]) is not int for k in KEYS):raise ValueError('accepted measured numeric contract required; tokens remain nonexecutable')
    if not 1<=value[KEYS[0]]<=2048 or not 1<=value[KEYS[2]]<=value[KEYS[1]]<=512:raise ValueError('target outside supported numeric bounds')
    return value

def sha(value):return hashlib.sha256(value).hexdigest()

def verify_data(receipt, original, baseline, phase_files, client_log, snapshot_checker):
    target=target_values(receipt['target'])
    if receipt['status']!='passed' or [r['label'] for r in receipt['phases']]!=list(LABELS):raise ValueError('complete phase sequence required')
    if original.get('enableChunkGeneration') is not False or baseline.get('enableChunkGeneration') is not False:raise ValueError('existing staged restart overlay required')
    synthetic={KEYS[0]:1 if target[KEYS[0]]!=1 else 2,KEYS[1]:1 if target[KEYS[1]]!=1 else 2,KEYS[2]:1}
    if baseline!={**original,**synthetic}:raise ValueError('synthetic baseline changed unrelated settings')
    disk_key='maxConcurrentDiskReads';alternate=3 if baseline[disk_key]!=3 else 4
    applied={**baseline,**target};unrelated={**applied,disk_key:alternate};undone={**baseline,disk_key:alternate}
    expected=[baseline,applied,unrelated,undone,undone,undone,undone,unrelated,unrelated,original]
    radii=[baseline[KEYS[0]],target[KEYS[0]],target[KEYS[0]],baseline[KEYS[0]],baseline[KEYS[0]],target[KEYS[0]],target[KEYS[0]],target[KEYS[0]],target[KEYS[0]],original[KEYS[0]]]
    counts=[0,1,0,1,0,1,0,0,0,int(original[KEYS[0]]!=target[KEYS[0]])]
    last=None;config_bytes={}
    for row,wanted,radius,count in zip(receipt['phases'],expected,radii,counts):
        label=row['label'];config_raw,snapshot_raw,summary_raw=phase_files[label]
        if sha(config_raw)!=row['config_sha256'] or sha(snapshot_raw)!=row['snapshot_sha256'] or sha(summary_raw)!=row['summary_sha256']:raise ValueError('captured bytes changed: '+label)
        if json.loads(config_raw)!=wanted:raise ValueError('persisted config/scope differs: '+label)
        snapshot=json.loads(snapshot_raw);snapshot_checker(snapshot,summary_raw.decode(),True,False)
        if snapshot['lodDistanceChunks']!=radius:raise ValueError('effective radius differs: '+label)
        start,end=row['client_start'],row['client_end']
        if type(start) is not int or type(end) is not int or not 0<=start<=end<=len(client_log) or last is not None and start!=last:raise ValueError('noncontiguous client log windows')
        if row['settled_seconds']<3:raise ValueError('bounded late-receipt observation missing')
        segment=client_log[start:end]
        if sha(segment)!=row['client_sha256']:raise ValueError('original client log interval changed')
        frames=SESSION.findall(segment)
        if frames!=[(b'20',str(radius).encode(),b'true')]*count:raise ValueError('actual session receipt count/value differs: '+label)
        last=end;config_bytes[label]=config_raw
    if config_bytes['undone']!=config_bytes['failed-save'] or config_bytes['undone']!=config_bytes['retry-preview']:raise ValueError('failed save changed actual persisted bytes')
    if SESSION.search(client_log[last:]):raise ValueError('late unexpected session receipt after final window')
    if b'unexpected session replacement in one-phase client' in client_log:raise ValueError('native world/connection changed')
    return {'status':'passed','phases':len(LABELS),'wire_repushes':sum(counts),'generation_running':True,'generation_configured':False}

def verify(root, receipt_path):
    from rig import regular,inside
    from server_control_smoke import check_snapshot
    root=Path(root).resolve();receipt_path=regular(Path(receipt_path).resolve());directory=receipt_path.parent
    if not receipt_path.is_relative_to(root/'evidence'):raise ValueError('run-owned numeric receipt required')
    receipt=json.loads(receipt_path.read_text());manifest=json.loads(regular(root/'manifest.json').read_text());runtime=json.loads(regular(root/'runtime.json').read_text())
    for key in ('run_id','run_hash','profile_hash','scenario_hash'):
        if receipt[key]!=manifest[key]:raise ValueError('run identity mismatch')
    if receipt['target']!=runtime['preset_contract']['conservative_target']:raise ValueError('frozen target differs')
    if regular(root/'preset-client.private.log').stat().st_size>32*1024*1024:raise ValueError('client log exceeds bound')
    for label in LABELS:
        for suffix in ('.config.json','.json','.txt'):
            if regular(directory/(label+suffix)).stat().st_size>65536:raise ValueError('phase capture exceeds bound')
    files={label:tuple(regular(directory/(label+suffix)).read_bytes() for suffix in ('.config.json','.json','.txt')) for label in LABELS}
    result=verify_data(receipt,json.loads(regular(directory/'original-config.json').read_text()),json.loads(regular(directory/'baseline-config.json').read_text()),files,regular(root/'preset-client.private.log').read_bytes(),check_snapshot)
    blocker=receipt['save_blocker']
    if not stat.S_ISDIR(blocker['mode']) or blocker['relative_path']!=receipt['config_relative']+'.tmp':raise ValueError('actual temporary-file directory blocker required')
    with regular(root/'server.private.log').open('rb') as stream:
        stream.seek(blocker['server_log_start']);failure=stream.read(blocker['server_log_end']-blocker['server_log_start']).lower()
    if b'failed to save config' not in failure or b'is a directory' not in failure:raise ValueError('original filesystem save failure absent')
    def setting(key,value):return 'set '+key+' '+str(value)
    original=json.loads((directory/'original-config.json').read_text())
    alternate=3 if original['maxConcurrentDiskReads']!=3 else 4
    expected_commands=[['preset conservative'],['preset apply'],[setting('maxConcurrentDiskReads',alternate)],['preset undo'],['preset conservative'],['preset apply'],['preset conservative'],['preset apply'],['preset undo'],[setting(k,original[k]) for k in (KEYS[1],KEYS[2],KEYS[0],'maxConcurrentDiskReads')]]
    for row,suffixes in zip(receipt['phases'],expected_commands):
        steps=receipt['steps'][row['command_start']:row['command_end']]
        if [step['command'] for step in steps]!=['lsslod '+suffix for suffix in suffixes+['diagnostics export']]:raise ValueError('numeric phase command sequence differs')
    for step in receipt['steps']:
        queued=step['queue_result'];actual=json.loads(regular(inside(root,'commands/results/'+queued['request_id']+'.json')).read_text())
        if actual!=queued or actual.get('status')!='response_observed':raise ValueError('original owned command result changed')
        with regular(root/'server.private.log').open('rb') as stream:stream.seek(actual['log_offset']);body=stream.read(256*1024)
        if step['expected_response'].encode() not in body:raise ValueError('owned console acknowledgement absent')
    return result
