import copy
import json
import tempfile
import unittest
from pathlib import Path
from check_source_run import inspect,make_proof,check_report,source_premises,ASSERTIONS
import test_concurrent_sources
from rig import digest

class SourceRunTest(unittest.TestCase):
    def fixture(self,root):
        root=Path(root);(root/'evidence').mkdir()
        def write(name,value): (root/name).write_text(json.dumps(value))
        def rows(name,value): (root/'evidence'/name).write_text(''.join(json.dumps(r)+'\n' for r in value))
        oracle,consumers,events,bounds=test_concurrent_sources.SourceCheckerTest().fixture()
        events[0]['time_ns']=1
        for i,letter in enumerate('ABCD'):
            for source in range(4):
                events.insert(0,dict(event='source_target_precondition',subject='RigSubject'+letter,source=source,
                    chunk_x=i*256+(-20 if source==2 else 8 if source in (0,1) else -8),chunk_z=-20 if source==2 else 8 if source in (0,3) else -8,
                    block_y=-64 if source==2 else 64,expected_block='bedrock' if source==2 else 'gold_block',loaded=source==0,retained=source==0,in_memory_present=source==0,
                    store_present=source==3,disk_present=source!=2,time_ns=0))
        for row in oracle+events:row['run_id']='run'
        for subject,values in consumers.items():
            values.insert(0,dict(event='consumer_ready'))
            for row in values:row.update(run_id='run',subject=subject)
            rows('consumer-'+subject+'.jsonl',values)
        rows('oracle.jsonl',oracle);rows('server-events.jsonl',events)
        regions=[];ticks=[dict(event='timing_ready',run_id='run'),dict(event='timing_applied'),dict(event='timing_closed',overflow=False)]
        for i,letter in enumerate('ABCD'):
            subject='RigSubject'+letter;connection='run-'+subject
            regions.extend([dict(event='join',subject=subject,connection_id=connection,time_ns=0),dict(event='product_registration_observed',subject=subject,connection_id=connection,time_ns=1),dict(event='owning_work',subject=subject,connection_id=connection,start_ns=2,end_ns=3,region_identity=str(i),context='owning-region',owns_region=True),dict(event='quit',subject=subject,connection_id=connection,time_ns=5)])
            ticks.append(dict(event='owning_tick',region_identity=str(i),start_ns=2,end_ns=4,failed=False))
            (root/('client-'+letter+'.private.log')).write_text('Server session config received (protocol v20,')
        for row in regions:row['run_id']='run'
        regions.append(dict(event='writer_closed',overflow=False))
        rows('region-events.jsonl',regions);rows('tick-events.jsonl',ticks)
        (root/'server.private.log').write_text('\n'.join('Player RigSubject'+letter+' registered for LSS LOD request processing' for letter in 'ABCD'))
        profile=dict(platform='folia');write('server-profile.json',profile);(root/'participants').mkdir();write('participants/server.json',profile)
        runtime=dict(server_profile=dict(path=str(root/'server-profile.json'),profile_hash=digest(profile)),launches=[dict(id=id) for id in ['server','client-A','client-B','client-C','client-D']])
        scenario=dict(checker='concurrent-sources',assertions=list(ASSERTIONS),debt_bounds_hash=digest(bounds))
        write('runtime.json',runtime);write('scenario.json',scenario);write('debt-bounds.json',bounds)
        manifest=dict(run_id='run',run_manifest={},run_hash=digest({}),profile_hash='profile',runtime_hash=digest(runtime),scenario_hash=digest(scenario))
        return manifest,events
    def test_unversioned_legacy_composition_cannot_pass_new_route(self):
        with tempfile.TemporaryDirectory() as root:
            manifest,_=self.fixture(root);result=inspect(root,manifest)
            self.assertIn('source scenario must pin target_schema=2',result['errors']);self.assertTrue(check_report(make_proof(result),manifest,root))
    def test_measured_flag_cannot_change_scenario_scope(self):
        with tempfile.TemporaryDirectory() as root:
            manifest,_=self.fixture(root);path=Path(root)/'runtime.json';runtime=json.loads(path.read_text())
            runtime['launches'][0]['argv']=['java','-Dlss.rig.measuredWorkload=true'];path.write_text(json.dumps(runtime));manifest['runtime_hash']=digest(runtime)
            self.assertIn('measured workload applicability differs from native producer flag',inspect(root,manifest)['errors'])
    def test_measured_proof_requires_all_registered_offers(self):
        with tempfile.TemporaryDirectory() as root:
            manifest,_=self.fixture(root);path=Path(root)/'runtime.json';runtime=json.loads(path.read_text())
            runtime['launches'][0]['argv']=['java','-Dlss.rig.measuredWorkload=true'];path.write_text(json.dumps(runtime));manifest['runtime_hash']=digest(runtime)
            path=Path(root)/'scenario.json';scenario=json.loads(path.read_text());scenario['measured_workload']=True;path.write_text(json.dumps(scenario));manifest['scenario_hash']=digest(scenario)
            with (Path(root)/'evidence/server-events.jsonl').open('a') as output:output.write(json.dumps(dict(event='workload_started',run_id='run',time_ns=0))+'\n')
            self.assertIn('all 23040 preregistered offers required',inspect(root,manifest)['errors'])
    def test_foreign_receipt_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            manifest,_=self.fixture(root);p=Path(root)/'evidence/consumer-RigSubjectA.jsonl';p.write_text(p.read_text().replace('"run_id": "run"','"run_id": "foreign"'))
            self.assertIn('foreign consumer evidence: RigSubjectA',inspect(root,manifest)['errors'])
    def test_stale_report_rejected_after_raw_mutation(self):
        with tempfile.TemporaryDirectory() as root:
            manifest,_=self.fixture(root);proof=make_proof(inspect(root,manifest));p=Path(root)/'client-A.private.log';p.write_text('no handshake')
            self.assertTrue(check_report(proof,manifest,root))
    def test_native_proto_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            _,events=self.fixture(root);next(r for r in events if r.get('source')==2)['in_memory_present']=True
            self.assertIn('generation source already has native protochunk',source_premises(events))
    def test_denial_failure_cannot_be_painted_green(self):
        with tempfile.TemporaryDirectory() as root:
            manifest,_=self.fixture(root);p=Path(root)/'evidence/server-events.jsonl';p.write_text(p.read_text().replace('"adapter_denial_reads": 1','"adapter_denial_reads": 0'))
            report=inspect(root,manifest);self.assertFalse(make_proof(report)['assertions']['actual_payload_source'])
    def test_missing_tick_evidence_fails(self):
        with tempfile.TemporaryDirectory() as root:
            manifest,_=self.fixture(root);(Path(root)/'evidence/tick-events.jsonl').unlink()
            self.assertTrue(check_report({},manifest,root))

if __name__=='__main__':unittest.main()
