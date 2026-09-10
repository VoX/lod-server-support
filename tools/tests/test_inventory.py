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


if __name__ == '__main__':
    unittest.main()
