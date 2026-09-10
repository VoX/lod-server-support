import unittest
from check_debt import check

class DebtTests(unittest.TestCase):
    def setUp(self):
        self.bounds={'held_sync':4,'held_gen':1,'send_queue':128,'backlog':512,'disk.pending':64,'generation.active':4,'store.queue':128}
        self.row={'event':'product_metrics','time_ns':1_000_000_000,'players':[{'name':'A','held_sync':0,'held_gen':0,'send_queue':0,'backlog':0}], 'disk':{'pending':0},'generation':{'active':0},'store':{'queue':0}}
    def test_actual_empty_debt_before_unregister(self):
        self.assertEqual('passed',check([self.row,dict(self.row,time_ns=2_000_000_000)],self.bounds,0,['A'])['status'])
    def test_destroyed_player_map_cannot_fake_drained(self):
        self.row['players']=[]
        self.assertEqual('failed',check([self.row,dict(self.row,time_ns=2_000_000_000)],self.bounds,0,['A'])['status'])
    def test_capacity_overrun_cannot_hide_in_final_zero(self):
        import copy
        bad=copy.deepcopy(self.row);bad['players'][0]['held_gen']=2
        self.assertEqual('failed',check([bad,self.row,dict(self.row,time_ns=2_000_000_000)],self.bounds,0,['A'])['status'])
