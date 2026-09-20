import copy
import unittest
from check_regions import check
class RegionEvidenceTest(unittest.TestCase):
    def rows(self):
        rows=[];accepted=[]
        for i,s in enumerate(('a','b')):
            rows.extend([{'event':'join','subject':s,'connection_id':s,'time_ns':0},
                         {'event':'owning_work','subject':s,'connection_id':s,'context':'owning-region','owns_region':True,'region_identity':str(i),'start_ns':10+i,'end_ns':20+i},
                         {'event':'quit','subject':s,'connection_id':s,'time_ns':100}])
            accepted.append({'subject':s,'connection_id':s,'accepted':True})
        rows.extend({'event':'product_registration_observed','subject':s,'connection_id':s,'time_ns':1} for s in ('a','b'))
        rows.append({'event':'writer_closed','overflow':False})
        return rows,accepted
    def test_actual_handshakes_required(self):
        rows,accepted=self.rows()
        self.assertEqual('failed',check(rows)['status'])
        self.assertEqual('passed',check(rows,accepted=accepted)['status'])
    def test_foreign_connection_cannot_supply_overlap(self):
        rows,accepted=self.rows();rows[1]['connection_id']='foreign'
        self.assertEqual('failed',check(rows,accepted=accepted)['status'])
    def test_stale_session_cannot_supply_overlap(self):
        rows,accepted=self.rows();rows[1]['start_ns']=101;rows[1]['end_ns']=110
        self.assertEqual('failed',check(rows,accepted=accepted)['status'])
    def test_same_region_fails(self):
        rows,accepted=self.rows();rows[4]['region_identity']='0'
        self.assertEqual('failed',check(rows,accepted=accepted)['status'])

    def test_pre_registration_overlap_is_not_product_overlap(self):
        rows,accepted=self.rows()
        for row in rows:
            if row.get('event')=='product_registration_observed':row['time_ns']=30
        self.assertEqual('failed',check(rows,accepted=accepted)['status'])
    def test_missing_structured_registration_fails(self):
        rows,accepted=self.rows()
        rows=[row for row in rows if row.get('event')!='product_registration_observed']
        self.assertEqual('failed',check(rows,accepted=accepted)['status'])
