import json, subprocess, tempfile, unittest
from pathlib import Path
from lines import *
class LineTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        git(self.root,'init');git(self.root,'config','user.name','Test');git(self.root,'config','user.email','test@example.invalid')
        self.manifest={'schema_version':1,'files':{'shared.java':{'kind':'identical'}},'nonproduction_patterns':{'docs/*':'line-local'}}
        self.a=self.commit('shared.java','base')
    def tearDown(self):self.tmp.cleanup()
    def commit(self,name,text):
        (self.root/name).write_text(text);git(self.root,'add',name);git(self.root,'commit','-m',text);return sha(self.root,'HEAD')
    def test_misspelled_rule_cannot_silently_skip_validation(self):
        self.manifest['files']['shared.java']={'kind':'identcal'}
        with self.assertRaises(Invalid):check(self.root,{'a':self.a},self.manifest)
    def test_shared_drift(self):
        b=self.commit('shared.java','changed')
        self.assertFalse(check(self.root,{'a':self.a,'b':b},self.manifest)['passed'])
    def test_new_file_is_not_auto_shared(self):
        b=self.commit('new.java','x');self.assertEqual(check(self.root,{'a':self.a,'b':b},self.manifest)['issues'][0]['result'],'unclassified')
    def test_adapted_residual_deleted_rejected(self):
        self.manifest['files']['shared.java']={'kind':'adapted','surface':'native-shape','tests':['ShapeTest'],'reviewed_blobs':{'a':git(self.root,'rev-parse',self.a+':shared.java').strip()}}
        b=self.commit('shared.java','shape token retained, logic deleted')
        self.assertEqual(check(self.root,{'a':b},self.manifest)['issues'][0]['result'],'adaptation-review-required')
    def test_missing_ref_is_error(self):
        with self.assertRaises(Invalid):inventory(self.root,{'a':'missing'},self.manifest)
    def test_dependency_order(self):
        b=self.commit('shared.java','b')
        with self.assertRaises(Invalid):plan(self.root,{'schema_version':1,'targets':{'a':self.a},'commits':[{'commit':b,'prerequisites':['f'*40]}]},self.manifest)
    def test_prepare_conflict_preserves_state(self):
        b=self.commit('shared.java','b');git(self.root,'checkout','-b','other',self.a);c=self.commit('shared.java','c')
        dst=self.root/'port'
        with self.assertRaises(Invalid):prepare(self.root,{'schema_version':1,'targets':{'a':c},'commits':[{'commit':b}]},self.manifest,'a',dst,'port/test')
        state=load(Path(git(dst,'rev-parse','--absolute-git-dir').strip())/'lss-port-state.json')
        self.assertEqual(state['state'],'conflict');self.assertEqual(state['proposal']['targets']['a'],c)
        self.assertEqual(state['conflicts'],['shared.java'])
    def test_resume_requires_saved_identity(self):
        b=self.commit('shared.java','b');git(self.root,'checkout','-b','other',self.a);c=self.commit('shared.java','c');dst=self.root/'port'
        with self.assertRaises(Invalid):prepare(self.root,{'schema_version':1,'targets':{'a':c},'commits':[{'commit':b}]},self.manifest,'a',dst,'port/test')
        (dst/'shared.java').write_text('reviewed resolution');git(dst,'add','shared.java')
        result=resume(dst)
        self.assertEqual(result['state'],'prepared-review-required')
        self.assertEqual((dst/'shared.java').read_text(),'reviewed resolution')
    def test_plan_preserves_dirty_deleted_files_and_index(self):
        b=self.commit('shared.java','b')
        (self.root/'shared.java').unlink();(self.root/'private.txt').write_text('untouched')
        before=git(self.root,'status','--porcelain');index=git(self.root,'write-tree')
        result=plan(self.root,{'schema_version':1,'targets':{'a':self.a},'commits':[{'commit':b}]},self.manifest)
        self.assertEqual(result['simulation']['a'][0]['result'],'private-index-applies')
        self.assertFalse((self.root/'shared.java').exists());self.assertEqual((self.root/'private.txt').read_text(),'untouched')
        self.assertEqual(git(self.root,'status','--porcelain'),before);self.assertEqual(git(self.root,'write-tree'),index)
    def test_generated_mutation_requires_provenance(self):
        self.manifest['files']['shared.java']={'kind':'generated','provenance':'recorded fixture source','reviewed_blobs':{'a':git(self.root,'rev-parse',self.a+':shared.java').strip()}}
        b=self.commit('shared.java','regenerated')
        self.assertEqual(check(self.root,{'a':b},self.manifest)['issues'][0]['result'],'provenance-review-required')
    def test_dirty_source_refused_before_worktree(self):
        (self.root/'dirty').write_text('mine');dst=self.root/'port'
        with self.assertRaises(Invalid):prepare(self.root,{'schema_version':1,'targets':{'a':self.a},'commits':[]},self.manifest,'a',dst,'port/test')
        self.assertFalse(dst.exists())
    def test_existing_patch_skips(self):
        b=self.commit('shared.java','b');dst=self.root/'port'
        state=prepare(self.root,{'schema_version':1,'targets':{'a':b},'commits':[{'commit':b}]},self.manifest,'a',dst,'port/test')
        self.assertEqual(state['completed'][0]['result'],'exact-patch-present')
if __name__=='__main__':unittest.main()
