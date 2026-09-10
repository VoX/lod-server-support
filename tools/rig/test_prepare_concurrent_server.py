import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import prepare_concurrent_server as prepare
from rig import digest,sha

class PrepareSourceRuntimeTest(unittest.TestCase):
    def test_paper_has_its_own_closure_and_no_inherited_folia_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);closure=root/'paper-cache'
            for name in ['libraries','versions','cache']:(closure/name).mkdir(parents=True)
            (closure/'cache'/'raw-cache-data').write_text('cache')
            def jar(path,plugin=None):
                with zipfile.ZipFile(path,'w') as archive:
                    archive.writestr('content.txt','test')
                    if plugin:archive.writestr('plugin.yml','name: '+plugin+'\nversion: "1"\nmain: example.Main\napi-version: "26.2"\n')
            jar(root/'paper.jar');jar(root/'candidate.jar','LodServerSupport');jar(root/'fixture.jar','LssRigPaperConcurrent')
            profile={'id':'clients','line':'26.2','platform':'fabric','components':[],'artifacts':[]}
            launches=[dict(id='client-'+letter,argv=['java','-Dlss.rig.subject=RigSubject'+letter,'-Dlss.rig.endpoint={endpoint}']) for letter in 'ABCD']
            runtime=dict(launches=launches,stage_files=[dict(source='unused',target='server/folia.jar')],generated_files={'server/config/folia-global.yml':'old'},cache={},candidate_artifacts=[],immutable_trees={'server/libraries':{'old':'hash'}},bind_endpoint='127.0.0.1:25574',server_profile={'old':True},world_digest='old')
            (root/'profile.json').write_text(json.dumps(profile));(root/'runtime.json').write_text(json.dumps(runtime))
            snapshot=root/'snapshot';(snapshot/'world').mkdir(parents=True);(snapshot/'world/level.dat').write_bytes(b'owned test fixture')
            files=[{'file':'world/level.dat','sha256':sha(snapshot/'world/level.dat')}]
            (snapshot/'snapshot.json').write_text(json.dumps({'files':files,'world_digest':digest(files)}))
            argv=['prepare','--world-snapshot',str(snapshot),'--client-runtime',str(root/'runtime.json'),'--client-profile',str(root/'profile.json'),'--java-home',str(root/'jdk'),'--server-candidate',str(root/'candidate.jar'),'--server-fixture',str(root/'fixture.jar'),'--server-cache',str(closure),'--server-launcher',str(root/'paper.jar'),'--output',str(root/'out')]
            with patch.object(prepare,'require_lock'),patch('sys.argv',argv),contextlib.redirect_stdout(io.StringIO()):prepare.main('paper')
            result=json.loads((root/'out/runtime.json').read_text())
            self.assertEqual(digest(files),result['world_digest'])
            self.assertTrue(any(row['target']=='server/world/level.dat' for row in result['stage_files']))
            scenario=json.loads((root/'out/scenario.json').read_text())
            self.assertEqual(2,scenario['target_schema']);self.assertEqual('paper',scenario['server_platform']);self.assertEqual('concurrent-sources',scenario['checker'])
            self.assertEqual(3,scenario['required_test_count'])
            self.assertNotIn('server/config/folia-global.yml',result['generated_files'])
            self.assertFalse(any(row['target']=='server/folia.jar' for row in result['stage_files']))
            self.assertEqual({},result['immutable_trees']['server/libraries'])
            self.assertEqual('LSS_RIG_SOURCES_READY',result['launches'][0]['ready_marker'])
            self.assertEqual(2,len(result['server_profile']['candidate_artifacts']))
            self.assertTrue(all(row['kind']=='plugin' for row in result['server_profile']['candidate_artifacts']))
            self.assertEqual(4,len(result['client_profiles']))

if __name__=='__main__':unittest.main()
