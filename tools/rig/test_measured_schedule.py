import unittest
from check_measured_schedule import check
class ScheduleProofTests(unittest.TestCase):
    def facts(self):
        rows=[]
        for subject in range(4):
            for sequence in range(1,5761):
                round=sequence-1;cell=round%256
                rows.append(dict(event='target',subject=f'RigSubject{chr(65+subject)}',target_sequence=sequence,
                    chunk_x=subject*256+12+cell//16,chunk_z=-7+cell%16,block_y=64,expected_source=0,allowed_sources=[0,1,3],
                    expected_block='diamond_block' if (round//256)%2==0 else 'gold_block',requires_ack=True,
                    offered_ns=1+round*125_000_000+10_000_000))
        return rows,[dict(event='offers_closed',time_ns=720_000_000_001)],1
    def test_source_policy_cannot_be_omitted_or_expanded_to_generation(self):
        for allowed in (None,[0],[0,1,2,3],[False,1,3],[0,1,3,3]):
            rows,events,origin=self.facts()
            if allowed is None:rows[0].pop('allowed_sources')
            else:rows[0]['allowed_sources']=allowed
            self.assertEqual('failed',check(rows,events,origin)['status'])
    def test_complete_offered_load(self):self.assertEqual('passed',check(*self.facts())['status'])
    def test_one_missing_offer_cannot_be_hidden_by_completed_subjects(self):
        rows,events,origin=self.facts();rows.pop();self.assertEqual('failed',check(rows,events,origin)['status'])
    def test_duplicate_cannot_replace_missing_target(self):
        rows,events,origin=self.facts();rows[-1]=rows[0];self.assertEqual('failed',check(rows,events,origin)['status'])
    def test_changed_cell_or_burst_schedule_fails(self):
        rows,events,origin=self.facts();rows[256]['chunk_x']+=1;self.assertEqual('failed',check(rows,events,origin)['status'])
        rows,events,origin=self.facts();rows[256]['offered_ns']=origin;self.assertEqual('failed',check(rows,events,origin)['status'])
