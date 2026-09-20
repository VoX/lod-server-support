import copy
import unittest
from unittest.mock import patch
import check_source_run as source

S=1_000_000_000
class SustainedProgressTest(unittest.TestCase):
    def fixture(self, delayed=False):
        sessions=[];oracle=[];windows=[]
        for letter in 'ABCD':
            subject='RigSubject'+letter;connection=subject+'-1'
            sessions.append(dict(subject=subject,connection_id=connection,start_ns=0,end_ns=840*S))
            for start in range(0,720,30):
                # A real outcome at31s still beats a32s revisit and120s deadline,
                # but leaves the first eligible30s window without any progress.
                resolved=(31 if delayed and letter=='A' and start==0 else start+1)*S
                oracle.append(dict(id=subject+str(start),subject=subject,offered_ns=start*S,resolved_ns=resolved,fault_removed_ns=start*S,expected='gold',actual='gold',expected_session=connection,delivery_session=connection))
            own=[t for t in oracle if t['subject']==subject]
            for start in range(0,720,30):
                windows.append(dict(subject=subject,start_ns=start*S,duration_seconds=30,eligible=any(t['offered_ns']<(start+30)*S and t['resolved_ns']>start*S for t in own),useful_outcomes=sum(start*S<=t['resolved_ns']<(start+30)*S for t in own)))
        return dict(platform='paper',required_subjects=4,sessions=sessions,oracle=oracle,progress_windows=windows,measurement_start_ns=0,measurement_end_ns=720*S,faults=[],queue_bounds_ok=True,cleanup_complete=True,drain_seconds=1)
    def check(self,value):
        with patch.object(source,'normalize',return_value=value) as normalized:
            result=source.sustained_progress([],{},[],'paper',0,{'status':'passed'},None)
            self.assertEqual(720*S,normalized.call_args.kwargs['end_ns'])
            return result
    def test_actual_progress_all_subjects_passes(self):
        self.assertEqual('passed',self.check(self.fixture())['status'])
    def test_delivery_before_revisit_does_not_waive_starvation(self):
        result=self.check(self.fixture(True))
        self.assertIn('starvation: RigSubjectA',result['errors'])
    def test_missing_subject_window_cannot_pass(self):
        value=self.fixture();value['progress_windows'].pop()
        self.assertIn('oracle observation windows omitted/duplicated',self.check(value)['errors'])
    def test_completed_subject_is_not_forced_to_keep_sending(self):
        value=self.fixture();value['oracle']=[t for t in value['oracle'] if t['subject']!='RigSubjectA' or t['offered_ns']==0]
        for row in value['progress_windows']:
            if row['subject']=='RigSubjectA' and row['start_ns']:
                row.update(eligible=False,useful_outcomes=0)
        self.assertEqual('passed',self.check(value)['status'])
    def test_raw_normalization_failure_is_not_ignored(self):
        with patch.object(source,'normalize',side_effect=ValueError('raw target proof failed')):
            result=source.sustained_progress([],{},[],'paper',0,{},None)
        self.assertEqual('failed',result['status'])
