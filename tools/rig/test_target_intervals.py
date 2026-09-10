import copy,unittest
from check_workload import check
from target_intervals import recovery_origin
S=1_000_000_000
class StrictTargets(unittest.TestCase):
 def setUp(self):
  self.oracle=[dict(event='session',subject='A',connection_id='one')]
  self.rows={'A':[dict(event='consumer_closed',overflow=False,held=0)]}
  for i in range(3):
   t=dict(event='target',id=str(i),subject='A',connection_id='one',offered_ns=(i*32+1)*S,target_sequence=i+1,chunk_x=2,chunk_z=3,block_y=64,dimension='overworld',world_generation='world1',cell_revision=i+1,predecessor_id=str(i-1) if i else None,requires_ack=True)
   self.oracle.extend([t,dict(event='target_acknowledged',id=str(i),time_ns=t['offered_ns']),dict(event='edit_applied',id=str(i),time_ns=t['offered_ns']+1,cell_revision=i+1,predecessor_id=t['predecessor_id'])])
   self.rows['A'].insert(-1,dict(event='target_committed',id=str(i),subject='A',connection_id='one',resolved_ns=(i*32+2)*S,body_received_ns=(i*32+2)*S-1,lease_active=True,body_bytes=12,body_id=i,wire_capture_id=i,wire_association='exact',cell_revision=i+1,chunk_x=2,chunk_z=3,dimension='overworld',world_generation='world1'))
  for row in list(self.rows['A']):
   if row.get('event')=='target_committed':
    row.update(source=0,column_timestamp=row['body_id']+100,local_session=1)
    self.rows['A'].append(dict(event='wire_capture',connection_id=row['connection_id'],wire_capture_id=row['wire_capture_id'],chunk_x=row['chunk_x'],chunk_z=row['chunk_z'],body_bytes=row['body_bytes'],column_timestamp=row['column_timestamp'],local_session=1,dimension=row['dimension'],source=0,arrival_ns=row['body_received_ns']))
 def result(self):return check(self.oracle,self.rows,1)
 def measured_fallback(self, source):
  for target in self.oracle:
   if target.get('event')=='target':target.update(expected_source=0,allowed_sources=[0,1,3],expected_block='gold_block')
  for row in self.rows['A']:
   if row.get('event') in ('target_committed','wire_capture'):row['source']=source
   if row.get('event')=='target_committed':row['expected_block']='gold_block'
 def test_current_existing_column_fallback_is_useful_delivery(self):
  for source in (0,1,3):
   self.measured_fallback(source);result=self.result()
   self.assertEqual('passed',result['status']);self.assertEqual(36,result['useful_body_bytes']['A'])
 def test_fallback_keeps_initial_source_and_current_content_laws(self):
  self.measured_fallback(1);self.oracle[1].pop('allowed_sources')
  self.assertIn('target delivered from wrong source: 0',self.result()['errors'])
  self.measured_fallback(2);self.assertEqual('failed',self.result()['status'])
  self.measured_fallback(1);self.rows['A'][0]['expected_block']='diamond_block';self.assertEqual('failed',self.result()['status'])
  self.measured_fallback(3);self.rows['A'][0]['resolved_ns']=66*S;self.assertEqual('failed',self.result()['status'])
 def test_invalid_fallback_policy_is_rejected_even_without_delivery(self):
  self.measured_fallback(1);self.oracle[1]['allowed_sources']=[0,1,2,3];self.rows['A'].pop(0)
  self.assertIn('invalid target source policy: 0',self.result()['errors'])
  self.oracle[1]['allowed_sources']=[0,1,3];self.oracle[1].pop('target_sequence')
  self.assertIn('invalid target source policy: 0',self.result()['errors'])
 def test_all_actual_deliveries(self):self.assertEqual('passed',self.result()['status'])
 def test_later_repeated_state_cannot_deliver_old_offer(self):
  self.rows['A'][0].update(resolved_ns=66*S,body_received_ns=66*S-1)
  self.assertIn('delivery outside original current revision: 0',self.result()['errors'])
 def test_delayed_acceptance_at_overwrite_fails(self):
  self.rows['A'][0]['resolved_ns']=33*S+1
  self.assertEqual('failed',self.result()['status'])
 def test_missing_delivery_not_superseded_success(self):
  self.rows['A'].pop(0);self.assertIn('target not delivered: 0',self.result()['errors'])
 def test_missing_revision_fails_measured(self):
  del self.oracle[1]['cell_revision'];self.assertEqual('failed',self.result()['status'])
 def test_reordered_owner_apply_fails(self):
  self.oracle[6]['time_ns']=1;self.assertEqual('failed',self.result()['status'])
 def test_wrong_world_and_revision_fail(self):
  self.rows['A'][0].update(world_generation='old',cell_revision=99);self.assertEqual('failed',self.result()['status'])
 def test_missing_wire_capture_or_ambiguous_association_fails(self):
  del self.rows['A'][0]['wire_capture_id'];self.assertEqual('failed',self.result()['status'])
  self.rows['A'][0].update(wire_capture_id=1,wire_association='ambiguous');self.assertEqual('failed',self.result()['status'])
 def test_wire_evidence_not_just_exact_label(self):
  self.rows['A']=[r for r in self.rows['A'] if r.get('event')!='wire_capture'];self.assertEqual('failed',self.result()['status'])
 def test_wire_field_substitution_fails(self):
  self.rows['A'][-1]['column_timestamp']=999;self.assertEqual('failed',self.result()['status'])
 def test_future_fault_never_resurrects_expired_target(self):
  self.assertEqual(0,recovery_origin(0,[dict(start_ns=121*S,end_ns=141*S)],200*S))
 def test_registered_fault_before_expiry_is_bounded(self):
  self.assertEqual(130*S,recovery_origin(0,[dict(start_ns=110*S,end_ns=130*S)],140*S))
 def test_future_fault_does_not_relabel_earlier_commit(self):
  self.assertEqual(0,recovery_origin(0,[dict(start_ns=110*S,end_ns=130*S)],50*S))
if __name__=='__main__':unittest.main()
