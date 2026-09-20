import copy,unittest
from source_schema import check

class SourceSchemaTest(unittest.TestCase):
    def fixture(self):
        oracle=[];events=[{'event':'source_preconditions_ready','time_ns':10}]
        for index,letter in enumerate('ABCD'):
            subject='RigSubject'+letter
            for x in (12,13,14):
                key=subject+str(x);chunk=index*256+x
                events.append(dict(event='diagnostic_loaded_precondition',subject=subject,chunk_x=chunk,chunk_z=0,source=0,block_y=64,expected_block='gold_block',loaded=True,retained=True,in_memory_present=True,disk_present=True,time_ns=5))
                oracle.extend([
                    dict(event='target',id=key,subject=subject,chunk_x=chunk,chunk_z=0,expected_source=0,requires_ack=True,dimension='minecraft:overworld',world_generation='run:world:1',cell_revision=1,predecessor_id=None),
                    dict(event='target_owner_precondition',id=key,subject=subject,chunk_x=chunk,chunk_z=0,owns_region=True,owner_name=subject,observed_ns=20,time_ns=30,region_identity='region'+letter,connection_id='connection'+letter),
                    dict(event='edit_applied',id=key,time_ns=40,owner_region_identity='region'+letter)])
        for index,letter in enumerate('ABCD'):
            subject='RigSubject'+letter
            for source in range(4):
                key=subject+str(source)+'-initial';x=index*256+(-20 if source==2 else 8 if source in (0,1) else -8);z=-20 if source==2 else 8 if source in (0,3) else -8
                oracle.append(dict(event='target',id=key,subject=subject,chunk_x=x,chunk_z=z,block_y=-64 if source==2 else 64,expected_block='diamond_block' if source==0 else 'bedrock' if source==2 else 'gold_block',expected_source=source,requires_ack=source==0,dimension='minecraft:overworld',world_generation='run:world:1',cell_revision=1,predecessor_id=None))
                if source==0:
                    oracle.extend([dict(event='target_owner_precondition',id=key,subject=subject,chunk_x=x,chunk_z=z,owns_region=True,owner_name=subject,observed_ns=20,time_ns=30,region_identity='region'+letter,connection_id='connection'+letter),dict(event='edit_applied',id=key,time_ns=40,owner_region_identity='region'+letter)])
        for letter in 'ABCD':
            oracle.append(dict(event='session',subject='RigSubject'+letter,connection_id='connection'+letter,time_ns=1))
            events.append(dict(event='session_end',subject='RigSubject'+letter,connection_id='connection'+letter,time_ns=50))
        return oracle,events,{'target_schema':2}
    def native_fixture(self,platform):
        o,e,s=self.fixture()
        for row in o:
            if row['event']=='target_owner_precondition':
                row.pop('region_identity');row.pop('owns_region')
                row.update(owner_kind='server-thread',owns_thread=True,owner_identity='server-thread:42',thread_id=42,thread_name='Server thread')
            if row['event']=='edit_applied':row.update(owner_identity='server-thread:42',owner_kind='server-thread')
        return o,e,s,platform
    def test_native_paper_owner(self):self.assertEqual([],check(*self.native_fixture('paper')))
    def test_native_fabric_owner(self):self.assertEqual([],check(*self.native_fixture('fabric')))
    def test_native_off_thread_rejected(self):
        o,e,s,p=self.native_fixture('paper');o[1]['owns_thread']=False
        self.assertIn('loaded mutation lacks actual native server-thread ownership',check(o,e,s,p))
    def test_native_thread_swap_rejected(self):
        o,e,s,p=self.native_fixture('fabric');o[2]['owner_identity']='server-thread:43'
        self.assertIn('loaded mutation lacks actual native server-thread ownership',check(o,e,s,p))
    def test_native_region_zero_cannot_substitute(self):
        o,e,s,p=self.native_fixture('paper');o[1]['owner_identity']='region0'
        self.assertIn('loaded mutation lacks actual native server-thread ownership',check(o,e,s,p))
    def test_valid(self):self.assertEqual([],check(*self.fixture()))
    def test_cannot_strip_schema(self):
        o,e,s=self.fixture()
        for row in o:
            if row['event']=='target':row.pop('cell_revision')
        self.assertIn('missing mandatory source revision schema',check(o,e,s))
    def test_scenario_pin(self):
        o,e,s=self.fixture();self.assertTrue(check(o,e,{}))
    def test_old_owner(self):
        o,e,s=self.fixture();o[1]['time_ns']=300_000_000;o[2]['time_ns']=300_000_001
        self.assertIn('loaded owner premise stale or after mutation',check(o,e,s))
    def test_foreign_region(self):
        o,e,s=self.fixture();o[2]['owner_region_identity']='other'
        self.assertIn('loaded mutation region differs from native owner premise',check(o,e,s))
    def test_distinct_edits(self):
        o,e,s=self.fixture();o[3]['chunk_x']=o[0]['chunk_x']
        self.assertIn('diagnostic edits must use twelve distinct prepared cells',check(o,e,s))
    def test_missing_seed(self):
        o,e,s=self.fixture();e.pop(1)
        self.assertIn('twelve distinct diagnostic loaded seed cells required',check(o,e,s))
    def test_old_session_owner_rejected(self):
        o,e,s=self.fixture();o[1]['connection_id']='retired'
        self.assertIn('loaded owner premise outside current source session',check(o,e,s))
    def test_no_loaded_ack(self):
        o,e,s=self.fixture();o[0]['requires_ack']=False
        self.assertIn('loaded target lacks ack-bound native mutation/ownership',check(o,e,s))

if __name__=='__main__':unittest.main()
