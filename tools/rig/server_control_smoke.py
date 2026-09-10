#!/usr/bin/env python3
"""Run-owned console acceptance for qualitative server presets and typed exports.
Uses only the existing supervisor command queue; never opens another process's stdin.
Run in a disposable settings smoke attempt, separate from performance/source lanes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import time
import uuid
from rig import alive, inside, regular, write

FIELDS={'schemaVersion','capturedAtMillis','serviceAvailable','enabled','generationEnabled',
        'generationConfiguredForRestart','lodDistanceChunks','uptimeSeconds','sentSections',
        'rawBytes','wireBytes','bandwidthWindowBytesPerSecond','versions'}
COMPONENTS={'LSS','MINECRAFT','LOADER','SODIUM','XAERO','VOXY','CONNECTOR','C2ME'}

def check_snapshot(snapshot,summary,running,configured):
    if set(snapshot)!=FIELDS:raise ValueError('typed export fields differ from allowlist')
    if snapshot['schemaVersion']!=1 or snapshot['serviceAvailable'] is not True or snapshot['enabled'] is not True:
        raise ValueError('active enabled service required')
    if snapshot['generationEnabled'] is not running or snapshot['generationConfiguredForRestart'] is not configured:
        raise ValueError('configured/running generation distinction incorrect')
    if ('(restart pending)' in summary)!=(running!=configured):raise ValueError('restart summary disagrees with typed export')
    versions=snapshot['versions']
    if set(versions)!={'components'} or set(versions['components'])!=COMPONENTS:raise ValueError('version component allowlist changed')
    if any(not isinstance(value,str) or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._+~-]{0,95}',value) for value in versions['components'].values()):
        raise ValueError('unbounded/non-version export metadata')
    for key in FIELDS-{'versions','serviceAvailable','enabled','generationEnabled','generationConfiguredForRestart'}:
        if type(snapshot[key]) is not int or snapshot[key]<0:raise ValueError('invalid numeric snapshot field: '+key)

def changed_keys(before,after):return {key for key in before.keys()|after.keys() if before.get(key)!=after.get(key)}

def expect_config(before,after,allowed):
    extra=changed_keys(before,after)-set(allowed)
    if extra:raise ValueError('preset changed unrelated configured keys: '+','.join(sorted(extra)))

class Driver:
    def __init__(self,root,config,brand,mode):
        self.root=root.resolve(strict=True);self.mode=mode
        self.manifest=json.loads(regular(self.root/'manifest.json').read_text())
        if self.manifest.get('status')!='running' or not alive(json.loads(regular(self.root/'owner.json').read_text())):
            raise ValueError('live owning rig supervisor required')
        runtime=json.loads(regular(self.root/'runtime.json').read_text())
        launch=next((item for item in runtime['launches'] if item['id']=='server'),None)
        if launch is None:raise ValueError('owned server launch required')
        self.server=inside(self.root,launch['cwd'])
        self.config=regular(config.resolve(strict=True))
        if not self.config.is_relative_to(self.server):raise ValueError('config must be the adopted file inside this owned server')
        self.command='lsslod' if brand=='lss' else 'vsslod'
        self.exports=self.server/(brand+'-diagnostics')
        self.queue=inside(self.root,'commands')
        if not self.queue.is_dir():raise ValueError('supervisor command queue not ready')
        self.id='settings-'+uuid.uuid4().hex
        self.output=inside(self.root,'evidence/'+self.id);self.output.mkdir()
        self.steps=[];self.snapshots={};self.blocker=None
        self.receipt={'schema_version':1,'run_id':self.manifest['run_id'],'run_hash':self.manifest['run_hash'],
                      'profile_hash':self.manifest['profile_hash'],'scenario_hash':self.manifest['scenario_hash'],
                      'mode':mode,'config_relative':str(self.config.relative_to(self.root)),
                      'steps':self.steps,'snapshots':self.snapshots,'status':'running'}
        shutil.copyfile(self.config,self.output/'original-config.json')
    def configured(self):return json.loads(self.config.read_text())
    def send(self,suffix,response):
        request_id=self.id+'-'+str(len(self.steps)).zfill(2)
        request={'launch_id':'server','command':self.command+' '+suffix,'timeout_seconds':15,'response_contains':response}
        write(self.queue/(request_id+'.json'),request)
        result_path=self.queue/'results'/(request_id+'.json');deadline=time.monotonic()+17
        while not result_path.exists():
            if time.monotonic()>deadline:raise ValueError('owned command result timeout: '+suffix)
            time.sleep(.1)
        result=json.loads(regular(result_path).read_text())
        step={'command':request['command'],'expected_response':response,'queue_result':result};self.steps.append(step)
        if result.get('status')!='response_observed':raise ValueError('console response not observed: '+suffix)
        with open(regular(self.root/'server.private.log'),'rb') as stream:
            stream.seek(result['log_offset']);text=stream.read(256*1024).decode(errors='replace')
        matching=[line for line in text.splitlines() if response in line]
        if not matching:raise ValueError('acknowledged command response missing from owned log')
        step['response_lines']=matching[:4]
        print(suffix+': observed',flush=True)
        return text
    def capture(self,label,running,configured):
        before=set(self.exports.glob('diagnostics-*.json')) if self.exports.exists() else set()
        self.send('diagnostics export','Diagnostics exported: ')
        fresh=set(self.exports.glob('diagnostics-*.json'))-before
        if len(fresh)!=1:raise ValueError('one fresh export pair required')
        path=regular(fresh.pop());summary_path=regular(path.with_suffix('.txt'))
        if max(path.stat().st_size,summary_path.stat().st_size)>65536:raise ValueError('export exceeds64KiB')
        snapshot=json.loads(path.read_text());summary=summary_path.read_text()
        check_snapshot(snapshot,summary,running,configured)
        shutil.copyfile(path,self.output/(label+'.json'));shutil.copyfile(summary_path,self.output/(label+'.txt'))
        self.snapshots[label]={'json_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'summary_sha256':hashlib.sha256(summary_path.read_bytes()).hexdigest(),'running':running,'configured':configured}
        return snapshot
    def preview(self):
        before=self.config.read_bytes()
        text=self.send('preset pregenerated-world','Use preset apply to persist, or leave the preview unapplied.')
        if 'Preset preview: SERVER GLOBAL;' not in text or 'Requires server restart. Running generation stays true.' not in text:
            raise ValueError('preview omitted global scope/restart/running behavior')
        if self.config.read_bytes()!=before:raise ValueError('preview persisted a mutation')
    def finish(self,error=None):
        if self.blocker is not None:
            try:self.blocker.rmdir();self.blocker=None
            except OSError as cleanup_error:
                self.receipt["blocker_cleanup_error"]=str(cleanup_error)
                error=error or cleanup_error
        self.receipt['status']='failed' if error else 'passed'
        if error:self.receipt['error']=str(error)
        shutil.copyfile(self.config,self.output/'final-config.json')
        self.receipt['final_config_sha256']=hashlib.sha256(self.config.read_bytes()).hexdigest()
        write(self.output/'receipt.json',self.receipt)
        print(str(self.output/'receipt.json'),flush=True)


def exercise(driver,mode,previous=None):
    driver.send('diag','Throughput: sent=')
    if mode=='after-restart':
        if previous is None:raise ValueError('prior stage-restart receipt required')
        prior=json.loads(regular(previous).read_text())
        if prior.get('status')!='passed' or prior.get('mode')!='stage-restart' or prior.get('run_id')==driver.manifest['run_id']:
            raise ValueError('successful staging in a distinct previous run required')
        if hashlib.sha256(driver.config.read_bytes()).hexdigest()!=prior['final_config_sha256']:
            raise ValueError('restarted server did not load the staged config bytes')
        driver.capture('after-restart',False,False)
        driver.send('preset undo','No preset application to undo.')
        driver.receipt['previous_receipt_sha256']=hashlib.sha256(previous.read_bytes()).hexdigest()
        return
    initial=driver.capture('initial',True,True)
    original_distance=initial['lodDistanceChunks']
    # Canonicalize the already-running value once so later full JSON comparisons do
    # not confuse newly serialized defaults with preset scope changes.
    driver.send('set lodDistanceChunks '+str(original_distance),'lodDistanceChunks = '+str(original_distance))
    baseline=driver.configured();baseline_bytes=driver.config.read_bytes()
    shutil.copyfile(driver.config,driver.output/'baseline-config.json')
    driver.preview()
    driver.capture('preview',True,True)
    failure=mode=='save-failure'
    if failure:
        blocker=driver.config.with_name(driver.config.name+'.tmp')
        if blocker.exists() or blocker.is_symlink():raise ValueError('temporary-save blocker already exists')
        blocker.mkdir();driver.blocker=blocker
    driver.send('preset apply','not saved — check server log.' if failure else 'running settings unchanged; saved.')
    driver.capture('applied',True,False)
    if failure:
        if driver.config.read_bytes()!=baseline_bytes:raise ValueError('failed preset save changed persisted bytes')
    else:
        applied=driver.configured();expect_config(baseline,applied,{'enableChunkGeneration'})
        if applied.get('enableChunkGeneration') is not False:raise ValueError('restart choice not persisted')
    alternate=original_distance+1 if original_distance<2048 else original_distance-1
    driver.send('set lodDistanceChunks '+str(alternate),'lodDistanceChunks = '+str(alternate))
    changed=driver.capture('unrelated-save',True,False)
    if changed['lodDistanceChunks']!=alternate:raise ValueError('unrelated runtime setting did not apply')
    if failure:
        if driver.config.read_bytes()!=baseline_bytes:raise ValueError('failed unrelated save changed persisted bytes')
    else:
        expect_config(baseline,driver.configured(),{'enableChunkGeneration','lodDistanceChunks'})
        if driver.configured().get('enableChunkGeneration') is not False:raise ValueError('unrelated save lost restart overlay')
    if mode=='stage-restart':
        driver.send('set lodDistanceChunks '+str(original_distance),'lodDistanceChunks = '+str(original_distance))
        driver.capture('staged-for-restart',True,False)
        expect_config(baseline,driver.configured(),{'enableChunkGeneration'})
        if driver.configured().get('enableChunkGeneration') is not False:raise ValueError('final unrelated save lost restart overlay')
        driver.receipt['restart_required']=True
        return
    driver.send('preset undo','Last preset settings restored; '+('not saved — check server log.' if failure else 'saved.'))
    undone=driver.capture('undone',True,True)
    if undone['lodDistanceChunks']!=alternate:raise ValueError('undo changed an unrelated setting')
    if failure:
        if driver.config.read_bytes()!=baseline_bytes:raise ValueError('failed undo changed persisted bytes')
        driver.blocker.rmdir();driver.blocker=None
    driver.send('set lodDistanceChunks '+str(original_distance),'lodDistanceChunks = '+str(original_distance))
    driver.capture('restored',True,True)
    expect_config(baseline,driver.configured(),set())

def verify_receipt(root,path):
    root=root.resolve(strict=True);path=regular(path.resolve(strict=True))
    if not path.is_relative_to(root/'evidence'):raise ValueError('receipt must belong to this run evidence')
    manifest=json.loads(regular(root/'manifest.json').read_text());receipt=json.loads(path.read_text())
    for key in ('run_id','run_hash','profile_hash','scenario_hash'):
        if receipt.get(key)!=manifest.get(key):raise ValueError('receipt identity mismatch: '+key)
    if receipt.get('status')!='passed':raise ValueError('attempt receipt did not pass')
    expected={'initial':(True,True),'preview':(True,True),'applied':(True,False),'unrelated-save':(True,False)}
    mode=receipt['mode']
    if mode=='after-restart':expected={'after-restart':(False,False)}
    elif mode=='stage-restart':expected['staged-for-restart']=(True,False)
    elif mode in ('apply-undo','save-failure'):expected.update(undone=(True,True),restored=(True,True))
    else:raise ValueError('unknown receipt mode')
    if set(receipt['snapshots'])!=set(expected):raise ValueError('required snapshot phase absent')
    for label,(running,configured) in expected.items():
        snapshot_path=regular(path.parent/(label+'.json'));summary_path=regular(path.parent/(label+'.txt'))
        claim=receipt['snapshots'][label]
        if hashlib.sha256(snapshot_path.read_bytes()).hexdigest()!=claim['json_sha256'] or hashlib.sha256(summary_path.read_bytes()).hexdigest()!=claim['summary_sha256']:
            raise ValueError('captured export bytes changed: '+label)
        check_snapshot(json.loads(snapshot_path.read_text()),summary_path.read_text(),running,configured)
    if not receipt['steps']:raise ValueError('required console steps absent')
    command_root=receipt['steps'][0]['command'].split(' ')[0]
    if command_root not in ('lsslod','vsslod'):raise ValueError('invalid server command root')
    suffixes=['diag','diagnostics export']
    if mode=='after-restart':suffixes.append('preset undo')
    else:
        initial=json.loads((path.parent/'initial.json').read_text())['lodDistanceChunks']
        alternate=initial+1 if initial<2048 else initial-1
        suffixes += ['set lodDistanceChunks '+str(initial),'preset pregenerated-world','diagnostics export','preset apply','diagnostics export','set lodDistanceChunks '+str(alternate),'diagnostics export']
        if mode!='stage-restart':suffixes += ['preset undo','diagnostics export']
        suffixes += ['set lodDistanceChunks '+str(initial),'diagnostics export']
    if [step['command'] for step in receipt['steps']]!=[command_root+' '+suffix for suffix in suffixes]:
        raise ValueError('required console command sequence differs')
    for step in receipt['steps']:
        result=step['queue_result'];actual=json.loads(regular(root/'commands/results'/(result['request_id']+'.json')).read_text())
        if actual!=result or actual.get('status')!='response_observed':raise ValueError('owned command evidence changed')
        with open(regular(root/'server.private.log'),'rb') as stream:
            stream.seek(actual['log_offset']);output=stream.read(256*1024).decode(errors='replace')
        if step['expected_response'] not in output:raise ValueError('console response absent from original log')
    final=regular(path.parent/'final-config.json')
    if hashlib.sha256(final.read_bytes()).hexdigest()!=receipt['final_config_sha256']:raise ValueError('final config capture changed')
    if mode in ('stage-restart','after-restart') and json.loads(final.read_text()).get('enableChunkGeneration') is not False:
        raise ValueError('restart choice absent from final persisted config')
    if mode!='after-restart':
        baseline=json.loads(regular(path.parent/'baseline-config.json').read_text())
        expect_config(baseline,json.loads(final.read_text()),{'enableChunkGeneration'} if mode=='stage-restart' else set())
    return {'status':'passed','mode':mode,'run_id':manifest['run_id'],'run_hash':manifest['run_hash']}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);p.add_argument('--config',type=Path);p.add_argument('--verify-receipt',type=Path)
    p.add_argument('--brand',choices=['lss','vss'],default='lss');p.add_argument('--mode',choices=['apply-undo','save-failure','stage-restart','after-restart']);p.add_argument('--previous-receipt',type=Path)
    args=p.parse_args()
    if args.verify_receipt:
        print(json.dumps(verify_receipt(args.run,args.verify_receipt)));raise SystemExit(0)
    if args.config is None or args.mode is None:p.error('--config and --mode are required for live execution')
    driver=Driver(args.run,args.config,args.brand,args.mode)
    try:exercise(driver,args.mode,args.previous_receipt)
    except Exception as error:driver.finish(error);raise SystemExit(1)
    else:driver.finish()
