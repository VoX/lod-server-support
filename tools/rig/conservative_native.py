"""New external derivation using maintained run-owned console Driver; no new product hooks."""
import hashlib
import json
from pathlib import Path
import shutil
import time
from server_control_smoke import Driver
from check_conservative_native import KEYS,target_values,verify

class ConservativeDriver(Driver):
    def __init__(self,root,config,brand='lss'):
        super().__init__(root,config,brand,'conservative-numeric')
        runtime=json.loads((self.root/'runtime.json').read_text())
        self.target=target_values(runtime['preset_contract']['conservative_target'])
        self.receipt.update(target=self.target,phases=[])
        self.client=self.root/'preset-client.private.log'
    def phase(self,label,commands):
        command_start=len(self.steps)
        for suffix,response in commands:self.send(suffix,response)
        self.capture(label,True,False)
        shutil.copyfile(self.config,self.output/(label+'.config.json'))
        # Complete a fixed observation window, including after the expected frame arrives.
        started=time.monotonic()
        while time.monotonic()-started<3:time.sleep(.1)
        end=self.client.stat().st_size
        if end>32*1024*1024 or end<self.client_offset:raise ValueError('client log rotated or exceeds bound')
        with self.client.open('rb') as stream:stream.seek(self.client_offset);segment=stream.read(end-self.client_offset)
        digest=lambda suffix:hashlib.sha256((self.output/(label+suffix)).read_bytes()).hexdigest()
        self.receipt['phases'].append({'label':label,'command_start':command_start,'command_end':len(self.steps),
            'client_start':self.client_offset,'client_end':end,'client_sha256':hashlib.sha256(segment).hexdigest(),
            'settled_seconds':time.monotonic()-started,'config_sha256':digest('.config.json'),
            'snapshot_sha256':digest('.json'),'summary_sha256':digest('.txt')})
        self.client_offset=end
    @staticmethod
    def setting(key,value):return ('set '+key+' '+str(value),key+' = '+str(value))
    def exercise(self):
        # Run after the existing qualitative stage-restart mode. Restore its exact final config.
        self.capture('numeric-initial',True,False)
        original=self.configured();radius=self.target[KEYS[0]]
        synthetic={KEYS[0]:1 if radius!=1 else 2,KEYS[1]:1 if self.target[KEYS[1]]!=1 else 2,KEYS[2]:1}
        self.receipt['synthetic_setup']='Temporary 1/2 radius and generation caps only to make changes observable; not selected performance values'
        for key in (KEYS[1],KEYS[2],KEYS[0]):self.send(*self.setting(key,synthetic[key]))
        self.capture('numeric-baseline',True,False)
        shutil.copyfile(self.config,self.output/'baseline-config.json')
        time.sleep(3)
        self.client_offset=self.client.stat().st_size
        preview=[('preset conservative','Use preset apply to apply this preview.')]
        self.phase('preview',preview)
        self.phase('applied',[('preset apply','saved.')])
        alternate=3 if original['maxConcurrentDiskReads']!=3 else 4
        self.phase('unrelated',[self.setting('maxConcurrentDiskReads',alternate)])
        self.phase('undone',[('preset undo','saved.')])
        self.phase('failure-preview',preview)
        blocker=self.config.with_name(self.config.name+'.tmp')
        if blocker.exists() or blocker.is_symlink():raise ValueError('preexisting save blocker')
        blocker.mkdir();self.blocker=blocker
        self.receipt['save_blocker']={'relative_path':str(blocker.relative_to(self.root)),'mode':blocker.stat().st_mode,'server_log_start':(self.root/'server.private.log').stat().st_size}
        self.phase('failed-save',[('preset apply','not saved — check server log.')])
        self.receipt['save_blocker']['server_log_end']=(self.root/'server.private.log').stat().st_size
        blocker.rmdir();self.blocker=None
        self.phase('retry-preview',preview)
        self.phase('retry-saved',[('preset apply','saved.')])
        self.phase('empty-undo',[('preset undo','saved.')])
        self.phase('restored',[self.setting(k,original[k]) for k in (KEYS[1],KEYS[2],KEYS[0],'maxConcurrentDiskReads')])
        if self.config.read_bytes()!=(self.output/'original-config.json').read_bytes():raise ValueError('numeric cleanup did not restore exact qualitative staged bytes')


def run(root,config):
    driver=ConservativeDriver(Path(root),Path(config))
    try:driver.exercise()
    except Exception as error:driver.finish(error);raise
    driver.finish()
    return verify(driver.root,driver.output/'receipt.json'),driver.output/'receipt.json'
