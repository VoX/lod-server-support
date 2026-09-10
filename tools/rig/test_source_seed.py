import json
import tempfile
import unittest
from pathlib import Path
from rig import write,digest
from check_source_seed import inspect,check_report

class SourceSeedTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        (self.root/'evidence').mkdir()
        native={'id':'paper-source','platform':'paper','route':'native'};write(self.root/'native.json',native)
        self.runtime={'launches':[{'id':'server','stop_stdin':'stop'}],'server_profile':{'path':str(self.root/'native.json'),'id':native['id'],'profile_hash':digest(native)}}
        self.scenario={'execution_route':'source-seed','requires_handshake':False}
        self.manifest={'run_id':'test','runtime_hash':digest(self.runtime),'scenario_hash':digest(self.scenario)}
        write(self.root/'runtime.json',self.runtime);write(self.root/'scenario.json',self.scenario)
        write(self.root/'evidence/source-seed-stop.json',{'run_id':'test','server_returncode':0})
        (self.root/'evidence/oracle.jsonl').write_text('')
        self.rows=[]
        for i in range(4):
            for source in range(4):
                self.rows.append(dict(event='source_target_precondition',run_id='test',time_ns=10,subject='RigSubject'+chr(65+i),source=source,
                    chunk_x=i*256+(-20 if source==2 else 8 if source in (0,1) else -8),chunk_z=-20 if source==2 else 8 if source in (0,3) else -8,
                    block_y=-64 if source==2 else 64,expected_block='bedrock' if source==2 else 'gold_block',
                    loaded=source==0,retained=source==0,store_present=source==3,disk_present=source!=2,in_memory_present=source==0))
        self.rows.extend(dict(row,event='source_generation_initial_precondition',time_ns=1) for row in list(self.rows) if row['source']==2)
        self.rows.append(dict(event='source_preparation_started',run_id='test',time_ns=5))
        self.rows.extend([dict(event='source_preconditions_ready',run_id='test',time_ns=20),dict(event='writer_closed',run_id='test',overflow=False)])
    def check(self):
        (self.root/'evidence/server-events.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in self.rows))
        return inspect(self.root,self.manifest)
    def test_exact_independent_prerequisites_pass_without_handshake(self):self.assertEqual('passed',self.check()['status'])
    def test_missing_duplicate_and_foreign_facts_fail(self):
        for mode in ('missing','duplicate','foreign'):
            original=[dict(r) for r in self.rows]
            if mode=='missing':self.rows.pop(0)
            elif mode=='duplicate':self.rows.insert(0,dict(self.rows[0]))
            else:self.rows[0]['run_id']='other'
            self.assertEqual('failed',self.check()['status']);self.rows=original
    def test_generated_or_loaded_disk_targets_fail(self):
        self.rows[2]['disk_present']=True;self.assertEqual('failed',self.check()['status'])
        self.rows[2]['disk_present']=False;self.rows[1]['loaded']=True;self.assertEqual('failed',self.check()['status'])
    def test_clients_and_unclean_exit_fail(self):
        self.rows.insert(-1,dict(event='join',run_id='test'));self.assertEqual('failed',self.check()['status'])
        self.rows.pop(-2);write(self.root/'evidence/source-seed-stop.json',{'run_id':'test','server_returncode':-15});self.assertEqual('failed',self.check()['status'])
    def test_report_must_match_current_raw_bytes(self):
        result=self.check();proof={'source_seed_report':result,'test_count':16}
        self.assertEqual([],check_report(proof,self.manifest,self.scenario,self.root))
        self.rows[0]['retained']=False;self.check()
        self.assertTrue(check_report(proof,self.manifest,self.scenario,self.root))
    def test_wrong_route_or_writer_overflow_fails(self):
        self.rows[-1]['overflow']=True;self.assertEqual('failed',self.check()['status'])
    def measured(self):
        self.runtime['launches'][0]['argv']=['java','-Dlss.rig.measuredWorkload=true']
        self.manifest['runtime_hash']=digest(self.runtime);write(self.root/'runtime.json',self.runtime)
        for i in range(4):
            for x in range(12,28):
                for z in range(-7,9):
                    self.rows.insert(-2,dict(event='measured_loaded_precondition',run_id='test',time_ns=10,
                        subject='RigSubject'+chr(65+i),chunk_x=i*256+x,chunk_z=z,source=0,block_y=64,
                        expected_block='gold_block',loaded=True,retained=True,disk_present=True,store_present=False))
    def test_measured_snapshot_requires_every_distinct_durable_cell(self):
        self.measured();result=self.check();self.assertEqual('passed',result['status']);self.assertEqual(1024,result['measured_target_count'])
        self.rows.pop(next(i for i,row in enumerate(self.rows) if row['event']=='measured_loaded_precondition'));self.assertEqual('failed',self.check()['status'])
    def test_measured_snapshot_cannot_reuse_one_cell_or_fake_retention(self):
        self.measured();at=next(i for i,row in enumerate(self.rows) if row['event']=='measured_loaded_precondition');self.rows[at+1]=dict(self.rows[at]);self.assertEqual('failed',self.check()['status'])
        self.rows[at]['retained']=False;self.assertIn('measured loaded cell not durably seeded and retained',self.check()['errors'])
    def test_measured_cells_cannot_be_added_to_an_unbound_diagnostic(self):
        self.measured();self.runtime['launches'][0]['argv']=['java'];self.manifest['runtime_hash']=digest(self.runtime)
        write(self.root/'runtime.json',self.runtime);self.assertEqual('failed',self.check()['status'])

    def test_initial_native_protochunk_is_not_untouched(self):
        row=next(row for row in self.rows if row['event']=='source_generation_initial_precondition')
        row['in_memory_present']=True
        self.assertIn('generation target exists before fixture seeding',self.check()['errors'])
    def test_final_pending_protochunk_and_missing_initial_proof_fail(self):
        self.rows[2]['in_memory_present']=True
        self.assertIn('generation target has a pending/native protochunk',self.check()['errors'])
        self.rows=[row for row in self.rows if row['event']!='source_generation_initial_precondition']
        self.assertIn('four initial generation absence observations required',self.check()['errors'])
