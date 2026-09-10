import importlib.util
from pathlib import Path
import unittest
import tempfile

spec = importlib.util.spec_from_file_location('inventory', Path(__file__).with_name('inventory.py'))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.moves = {'moves': [{'class': 'a.Test', 'before_task': ':fabric:test', 'after_task': ':common:test'}]}
        self.before = {'cases': [dict(identity='a.Test#case[1]', outcome='passed', task=':fabric:test'),
                                 dict(identity='a.Test#case[2]', outcome='skipped', task=':fabric:test')]}
        self.after = {'cases': [dict(c, task=':common:test') for c in self.before['cases']]}

    def test_preserves_parameter_case_and_assumption_identity(self):
        self.assertEqual([], inventory.compare(self.before, self.after, self.moves)['problems'])

    def test_missing_parameter_fails(self):
        self.after['cases'].pop()
        self.assertTrue(inventory.compare(self.before, self.after, self.moves)['problems'])

    def test_silent_new_skip_fails(self):
        self.after['cases'][0]['outcome'] = 'skipped'
        self.assertTrue(inventory.compare(self.before, self.after, self.moves)['problems'])

    def test_duplicate_execution_fails(self):
        self.after['cases'].append(self.after['cases'][0])
        self.assertTrue(inventory.compare(self.before, self.after, self.moves)['problems'])

    def test_stale_old_reports_fail(self):
        self.after['cases'].append(self.before['cases'][0])
        self.assertTrue(inventory.compare(self.before, self.after, self.moves)['problems'])

    def test_missing_baseline_fails(self):
        self.assertTrue(inventory.compare({'cases': []}, self.after, self.moves)['problems'])

    def test_reappeared_source_is_rejected_before_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'new.java').write_text('test')
            moves = {'moves': [{'class': 'Test', 'before': 'old.java', 'after': 'new.java'}]}
            self.assertEqual([], inventory.validate_moves(root, moves))
            (root / 'old.java').write_text('test')
            self.assertTrue(inventory.validate_moves(root, moves))


    def declare_addition(self):
        self.moves['post_migration_additions'] = [dict(identity='a.Test#new()', task=':common:test',
            outcome='passed', count=1, rationale='Actual later regression control', introducing_commit='a'*40)]
        self.after['cases'].append(dict(identity='a.Test#new()',task=':common:test',outcome='passed'))

    def test_exact_reviewed_addition_preserves_old_inventory(self):
        self.declare_addition()
        result=inventory.compare(self.before,self.after,self.moves)
        self.assertEqual([],result['problems']);self.assertEqual(2,result['before_cases'])
        self.assertEqual(3,result['after_cases']);self.assertEqual(1,result['declared_addition_cases'])

    def test_unknown_addition_still_fails(self):
        self.after['cases'].append(dict(identity='a.Test#unknown()',task=':common:test',outcome='passed'))
        self.assertTrue(inventory.compare(self.before,self.after,self.moves)['problems'])

    def test_addition_cannot_excuse_lost_or_changed_original(self):
        for mutation in ('lost','changed','duplicate'):
            with self.subTest(mutation=mutation):
                self.setUp();self.declare_addition()
                if mutation=='lost':self.after['cases'].pop(0)
                elif mutation=='changed':self.after['cases'][0]['outcome']='failed'
                else:self.after['cases'].append(dict(self.after['cases'][0]))
                self.assertTrue(inventory.compare(self.before,self.after,self.moves)['problems'])

    def test_missing_wrong_task_outcome_and_duplicate_addition_fail(self):
        for mutation in ('missing','task','failed','skipped','duplicate','other-task-duplicate'):
            with self.subTest(mutation=mutation):
                self.setUp();self.declare_addition()
                if mutation=='missing':self.after['cases'].pop()
                elif mutation=='task':self.after['cases'][-1]['task']=':paper:test'
                elif mutation in ('failed','skipped'):self.after['cases'][-1]['outcome']=mutation
                else:self.after['cases'].append(dict(self.after['cases'][-1],task=':paper:test' if mutation=='other-task-duplicate' else ':common:test'))
                self.assertTrue(inventory.compare(self.before,self.after,self.moves)['problems'])

    def test_preexisting_baseline_identity_cannot_be_declared(self):
        self.declare_addition();self.before['cases'].append(dict(self.after['cases'][-1],task=':paper:test'))
        self.assertTrue(inventory.compare(self.before,self.after,self.moves)['problems'])

    def test_invalid_and_duplicate_declarations_fail(self):
        for field,value in [('count',True),('count',2),('task',':paper:test'),('outcome','skipped'),
                            ('rationale',''),('introducing_commit','short'),('identity','foreign.Test#new()')]:
            with self.subTest(field=field,value=value):
                self.setUp();self.declare_addition();self.moves['post_migration_additions'][0][field]=value
                self.assertTrue(inventory.compare(self.before,self.after,self.moves)['problems'])
        self.setUp();self.declare_addition();self.moves['post_migration_additions']*=2
        self.assertTrue(inventory.compare(self.before,self.after,self.moves)['problems'])


if __name__ == '__main__':
    unittest.main()
