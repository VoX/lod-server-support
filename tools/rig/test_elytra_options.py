"""Native Options data-fixer input contract; does not claim a native launch."""
import ast,unittest
from pathlib import Path

class ElytraOptions(unittest.TestCase):
 def test_target_defaults_do_not_enter_legacy_numeric_key_datafixer(self):
  tree=ast.parse(Path(__file__).with_name('prepare_elytra_target.py').read_text())
  rows=[]
  for node in ast.walk(tree):
   if isinstance(node,ast.Assign)and any(isinstance(t,ast.Subscript)and isinstance(t.slice,ast.Constant)and t.slice.value=='elytra-target/options.txt'for t in node.targets):rows.append(ast.literal_eval(node.value))
  self.assertEqual(1,len(rows));options=dict(row.split(':',1)for row in rows[0].splitlines())
  self.assertEqual('false',options['onboardAccessibility']);self.assertEqual('false',options['pauseOnLostFocus'])
  self.assertFalse(any(key.startswith('key_')for key in options),'default jump/sneak bindings need no serialized override; absent data version invokes legacy numeric conversion')
if __name__=='__main__':unittest.main()
