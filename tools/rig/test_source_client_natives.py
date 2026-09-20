import copy,tempfile,unittest
from pathlib import Path
from source_client_natives import apply,prepare_directory,PREFIX
class SourceClientNativesTest(unittest.TestCase):
 def runtime(self):return {'launches':[{'id':'server','argv':['java']},*[{'id':'client-'+x,'argv':['java','-Dlss.rig.subject=RigSubject'+x,'Main']} for x in 'ABCD']]}
 def test_only_one_distinct_owned_flag_added_per_client(self):
  original=self.runtime();changed=apply(copy.deepcopy(original));paths=[]
  for before,after in zip(original['launches'],changed['launches']):
   flags=[x for x in after['argv'] if x.startswith(PREFIX)]
   if before['id']=='server':self.assertEqual(before,after);continue
   self.assertEqual([x for x in after['argv'] if not x.startswith(PREFIX)],before['argv']);self.assertEqual(len(flags),1);paths.extend(flags)
  self.assertEqual(len(set(paths)),4);self.assertEqual(changed,apply(copy.deepcopy(changed)))
 def test_duplicate_and_foreign_paths_rejected_without_partial_mutation(self):
  for flags in [[PREFIX+'/tmp/shared'],[PREFIX+'{run}/clients/RigSubjectD/lwjgl-native']*2]:
   rt=self.runtime();rt['launches'][-1]['argv']+=flags;before=copy.deepcopy(rt)
   with self.assertRaises(ValueError):apply(rt)
   self.assertEqual(rt,before)
 def test_wrong_subject_and_duplicate_composition_rejected(self):
  rt=self.runtime();rt['launches'][1]['argv'][1]='-Dlss.rig.subject=RigSubjectB'
  with self.assertRaises(ValueError):apply(rt)
  rt=self.runtime();rt['launches'].append(copy.deepcopy(rt['launches'][-1]))
  with self.assertRaises(ValueError):apply(rt)
 def test_runtime_missing_override_rejected(self):
  with tempfile.TemporaryDirectory() as root:
   with self.assertRaises(ValueError):prepare_directory(root,self.runtime()['launches'][1])
 def test_materialized_directories_are_owned_distinct_and_symlink_free(self):
  with tempfile.TemporaryDirectory() as root:
   paths=[prepare_directory(root,x) for x in apply(self.runtime())['launches'][1:]]
   self.assertEqual(len(set(paths)),4)
   self.assertTrue(all(p.is_dir() and p.resolve().is_relative_to(Path(root).resolve()) for p in paths))
 def test_ancestor_symlink_and_file_rejected(self):
  for symlink in [False,True]:
   with tempfile.TemporaryDirectory() as root,tempfile.TemporaryDirectory() as outside:
    parent=Path(root)/'clients'
    if symlink:parent.symlink_to(outside,target_is_directory=True)
    else:parent.write_text('occupied')
    with self.assertRaises(ValueError):prepare_directory(root,apply(self.runtime())['launches'][1])
 def test_foreign_generic_launch_unchanged(self):
  with tempfile.TemporaryDirectory() as root:self.assertIsNone(prepare_directory(root,{'id':'server','argv':['java']}))
if __name__=='__main__':unittest.main()
