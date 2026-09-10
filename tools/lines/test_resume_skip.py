import json, tempfile, unittest
from pathlib import Path
import lines

class ResumeSkipTests(unittest.TestCase):
    def scenario(self, equivalent):
        with tempfile.TemporaryDirectory(prefix='lss-resume-real-git-') as temp:
            root=Path(temp)/'source';root.mkdir()
            def git(*args):return lines.git(root,*args).strip()
            git('init');git('config','user.name','Test');git('config','user.email','test@example.invalid')
            def commit(path,text,message):
                (root/path).write_text(text);git('add',path);git('commit','-m',message);return lines.sha(root,'HEAD')
            base=commit('a.txt','base\n','base')
            a=commit('a.txt','source A\n','A conflicts')
            b=commit('b.txt','body B\n','source B')
            c=commit('c.txt','body C\n','source C')
            git('checkout','-b','target',base)
            if equivalent:
                b_target=commit('b.txt','body B\n','independent equivalent B')
                self.assertNotEqual(b,b_target)
                self.assertNotEqual(0,__import__('subprocess').run(['git','-C',str(root),'merge-base','--is-ancestor',b,'HEAD'],capture_output=True).returncode)
            else:
                # The exact B is in target history, but target has a conflicting A.
                git('reset','--hard',b)
            target=commit('a.txt','target A\n','target conflicts with A')
            destination=Path(temp)/'port'
            manifest={'schema_version':1,'files':{p:{'kind':'identical'} for p in ('a.txt','b.txt','c.txt')}}
            batch={'schema_version':1,'targets':{'target':target},'commits':[{'commit':x} for x in (a,b,c)]}
            # For ancestor B, A is also ancestor: remove A ancestry via merge history
            # while retaining its exact B descendant is impossible. Use source A
            # independent of B so target can contain B without already containing A.
            if not equivalent:
                git('checkout','-b','independent-a',base)
                a=commit('a.txt','source A\n','independent conflicting A')
                # Different parent from original A would still be equivalent if
                # original A stayed target history; choose distinct actual patch.
                a=commit('a.txt','source A second\n','independent A second')
                git('checkout','target')
                batch['commits'][0]={'commit':a}
            before_head=lines.sha(root,'HEAD');before_status=git('status','--porcelain');before_index=git('write-tree')
            with self.assertRaises(lines.Invalid):lines.prepare(root,batch,manifest,'target',destination,'port/control')
            statepath=Path(lines.git(destination,'rev-parse','--absolute-git-dir').strip())/'lss-port-state.json'
            self.assertEqual(a,lines.load(statepath)['failed_commit'])
            (destination/'a.txt').write_text('reviewed resolution\n');lines.git(destination,'add','a.txt')
            try:
                result=lines.resume(destination)
            except lines.Invalid:
                state=lines.load(statepath)
                print(json.dumps({'control':'patch-equivalent' if equivalent else 'ancestor',
                    'failed_commit_is_B':state['failed_commit']==b,'conflicts':state['conflicts'],
                    'saved_state':state['state'],'completed':state['completed'],
                    'git_status':lines.git(destination,'status','--porcelain'),
                    'cherry_pick_head_is_B':lines.git(destination,'rev-parse','CHERRY_PICK_HEAD').strip()==b},sort_keys=True))
                raise
            self.assertEqual('prepared-review-required',result['state'])
            self.assertEqual(['resolved-review-required','exact-patch-present','picked'],[r['result'] for r in result['completed']])
            self.assertEqual([a,b,c],[r['commit'] for r in result['completed']])
            self.assertEqual(result,lines.load(statepath))
            self.assertEqual('body B\n',(destination/'b.txt').read_text())
            self.assertEqual('body C\n',(destination/'c.txt').read_text())
            self.assertEqual(before_head,lines.sha(root,'HEAD'));self.assertEqual(before_status,git('status','--porcelain'));self.assertEqual(before_index,git('write-tree'))
    def test_resume_skips_existing_ancestor_then_picks_next(self):self.scenario(False)
    def test_resume_skips_exact_patch_equivalent_then_picks_next(self):self.scenario(True)

if __name__=='__main__':unittest.main()
