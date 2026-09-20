import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from commands import Commands
class CommandQueueTest(unittest.TestCase):
    def test_actual_owned_stdin(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);log=open(root/'server.private.log','wb')
            proc=subprocess.Popen([sys.executable,'-u','-c','import sys; print(sys.stdin.readline(),flush=True)'],stdin=subprocess.PIPE,stdout=log)
            try:
                queue=Commands(root,[({'id':'server'},proc)])
                (root/'commands/a.json').write_text(json.dumps({'launch_id':'server','command':'literal $() `no shell`'}))
                queue.poll();proc.wait(timeout=3);log.close()
                self.assertEqual('literal $() `no shell`\n\n',(root/'server.private.log').read_text())
                self.assertEqual('submitted',json.loads((root/'commands/results/a.json').read_text())['status'])
            finally:
                if proc.poll() is None:proc.kill();proc.wait()
                if not log.closed:log.close()
                proc.stdin.close()
    def test_foreign_target_and_multiline_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);queue=Commands(root,[])
            path=root/'commands/a.json';path.write_text(json.dumps({'launch_id':'foreign','command':'stop'}))
            with self.assertRaisesRegex(ValueError,'not owned'):queue.poll()
            path.write_text(json.dumps({'launch_id':'foreign','command':'one\ntwo'}))
            with self.assertRaisesRegex(ValueError,'bounded line'):queue.poll()
