import unittest
from candidate_identity import candidate_bindings
class CandidateIdentityTest(unittest.TestCase):
 def bind(self,paper,include_client=True):
  rows=[{'sha256':'b'*64,'metadata':{'paper':paper}}]
  stages=[{'target':'server/plugin.jar','sha256':'b'*64}]
  if include_client:
   rows.append({'sha256':'a'*64,'metadata':{'fabric':{'id':'lss'}}});stages.append({'target':'client/lss.jar','sha256':'a'*64})
  rt={'candidate_artifacts':rows,'stage_files':stages};hashes={r['target']:r['sha256'] for r in stages}
  return candidate_bindings(rt,hashes.__getitem__)
 def test_actual_paper_fixture_metadata_is_not_product(self):
  # Actual 26.2 fixture plugin.yml identity: package-private serializer seam.
  result=self.bind({'name':'LssRigPaperConcurrent','main':'dev.vox.lss.paper.PaperSourceProbe'})
  self.assertEqual({'client/lss.jar':'a'*64},result)
 def test_lss_and_vss_exact_native_entrypoint(self):
  for name in ['LodServerSupport','VoxyServerSide']:
   with self.subTest(name=name):self.assertIn('server/plugin.jar',self.bind({'name':name,'main':'dev.vox.lss.paper.LSSPaperPlugin'}))
 def test_product_name_wrong_entrypoint_rejected_even_with_client(self):
  with self.assertRaisesRegex(ValueError,'inconsistent'):self.bind({'name':'LodServerSupport','main':'dev.vox.lss.paper.PaperSourceProbe'})
 def test_product_entrypoint_wrong_name_rejected_even_with_client(self):
  with self.assertRaisesRegex(ValueError,'inconsistent'):self.bind({'name':'Unknown','main':'dev.vox.lss.paper.LSSPaperPlugin'})
 def test_fixture_only_cannot_supply_candidate(self):
  with self.assertRaisesRegex(ValueError,'no metadata-bound'):self.bind({'name':'LssRigPaperConcurrent','main':'dev.vox.lss.paper.PaperSourceProbe'},False)
 def test_native_mod_roles_unchanged(self):
  for metadata in [{'fabric':{'id':'lss'}},{'neoforge':{'mods':[{'modId':'lss'}]}}]:
   rt={'candidate_artifacts':[{'sha256':'a'*64,'metadata':metadata}],'stage_files':[{'target':'native.jar','sha256':'a'*64}]}
   self.assertEqual({'native.jar':'a'*64},candidate_bindings(rt,lambda p:'a'*64))
if __name__=='__main__':unittest.main()
