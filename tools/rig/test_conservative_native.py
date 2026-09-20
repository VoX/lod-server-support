"""Synthetic checker controls, not native outcomes or measured preset selection."""
import copy
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parent))
from server_control_smoke import check_snapshot,FIELDS,COMPONENTS
from check_conservative_native import verify_data,sha,KEYS,LABELS,target_values

class Controls(unittest.TestCase):
    def setUp(self):
        self.target=dict(zip(KEYS,(5,8,4))) # Synthetic mutation-control values only.
        self.original={**dict(zip(KEYS,(9,12,6))),'enableChunkGeneration':False,'maxConcurrentDiskReads':0,'requireServicePermission':True,'worldOverrides':{'fixture-world':{'lodDistanceChunks':17}}}
        self.baseline={**self.original,**dict(zip(KEYS,(1,1,1)))}
        applied={**self.baseline,**self.target};unrelated={**applied,'maxConcurrentDiskReads':3};undone={**self.baseline,'maxConcurrentDiskReads':3}
        configs=[self.baseline,applied,unrelated,undone,undone,undone,undone,unrelated,unrelated,self.original]
        radii=[1,5,5,1,1,5,5,5,5,9];counts=[0,1,0,1,0,1,0,0,0,1]
        self.log=b'old handshake Server session config received (protocol v20, LOD distance: 5 chunks, enabled: true)\n'
        self.receipt={'status':'passed','target':self.target,'phases':[]};self.files={}
        for label,config,radius,count in zip(LABELS,configs,radii,counts):
            snap={k:0 for k in FIELDS};snap.update(schemaVersion=1,serviceAvailable=True,enabled=True,generationEnabled=True,generationConfiguredForRestart=False,lodDistanceChunks=radius,versions={'components':{k:'fixture' for k in COMPONENTS}})
            raw=tuple(json.dumps(v,sort_keys=True).encode() for v in (config,snap))+(b'generation true (restart pending)',)
            self.files[label]=raw;start=len(self.log)
            self.log+=(f'Server session config received (protocol v20, LOD distance: {radius} chunks, enabled: true)\n'.encode()*count)+b'ordinary client tick\n'
            self.receipt['phases'].append({'label':label,'client_start':start,'client_end':len(self.log),'settled_seconds':3.1,'client_sha256':sha(self.log[start:]),'config_sha256':sha(raw[0]),'snapshot_sha256':sha(raw[1]),'summary_sha256':sha(raw[2])})
    def verify(self):return verify_data(self.receipt,self.original,self.baseline,self.files,self.log,check_snapshot)
    def change_file(self,label,index,mutate):
        raw=list(self.files[label]);data=json.loads(raw[index]);mutate(data);raw[index]=json.dumps(data,sort_keys=True).encode();self.files[label]=tuple(raw)
        next(r for r in self.receipt['phases'] if r['label']==label)[('config_sha256','snapshot_sha256','summary_sha256')[index]]=sha(raw[index])
    def test_complete_synthetic_evidence(self):self.assertEqual(self.verify()['phases'],10)
    def test_different_original_radius_requires_restore_frame(self):
        self.assertNotEqual(self.original[KEYS[0]],self.target[KEYS[0]])
        self.assertEqual(self.verify()['wire_repushes'],4)
    def test_same_original_radius_does_not_require_restore_frame(self):
        self.original[KEYS[0]]=self.target[KEYS[0]]
        self.change_file('restored',0,lambda d:d.update(lodDistanceChunks=5))
        self.change_file('restored',1,lambda d:d.update(lodDistanceChunks=5))
        row=self.receipt['phases'][-1]
        self.log=self.log[:row['client_start']]+b'ordinary client tick\n'
        row['client_end']=len(self.log);row['client_sha256']=sha(self.log[row['client_start']:])
        self.assertEqual(self.verify()['wire_repushes'],3)
    def test_tokens_are_not_executable_values(self):
        with self.assertRaises(ValueError):target_values(dict(zip(KEYS,('@MEASURED_RADIUS@','@MEASURED_GLOBAL@','@MEASURED_PER_PLAYER@'))))
    def test_saved_bytes_during_failed_save_rejected_even_with_new_hash(self):
        self.change_file('failed-save',0,lambda d:d.update(self.target))
        with self.assertRaisesRegex(ValueError,'persisted'):self.verify()
    def test_console_success_cannot_replace_effective_export(self):
        self.change_file('failed-save',1,lambda d:d.update(lodDistanceChunks=1))
        with self.assertRaisesRegex(ValueError,'effective'):self.verify()
    def test_undo_cannot_restore_unrelated_later_edit(self):
        self.change_file('undone',0,lambda d:d.update(maxConcurrentDiskReads=0))
        with self.assertRaisesRegex(ValueError,'persisted'):self.verify()
    def test_numeric_undo_cannot_consume_restart_overlay(self):
        self.change_file('empty-undo',0,lambda d:d.update(enableChunkGeneration=True))
        with self.assertRaisesRegex(ValueError,'persisted'):self.verify()
    def test_old_matching_receipt_does_not_satisfy_apply(self):
        row=self.receipt['phases'][1];row['client_end']=row['client_start'];row['client_sha256']=sha(b'')
        with self.assertRaisesRegex(ValueError,'receipt count'):self.verify()
    def test_late_duplicate_rejected(self):
        self.log+=b'Server session config received (protocol v20, LOD distance: 9 chunks, enabled: true)\n'
        with self.assertRaisesRegex(ValueError,'late unexpected'):self.verify()
    def test_missing_phase_rejected(self):
        self.receipt['phases'].pop(7)
        with self.assertRaisesRegex(ValueError,'complete phase'):self.verify()
    def test_noncontiguous_log_interval_rejected(self):
        self.receipt['phases'][3]['client_start']+=1
        with self.assertRaisesRegex(ValueError,'noncontiguous'):self.verify()
    def test_settle_window_required(self):
        self.receipt['phases'][0]['settled_seconds']=0
        with self.assertRaisesRegex(ValueError,'late-receipt'):self.verify()

if __name__=='__main__':unittest.main(verbosity=2)
