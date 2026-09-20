"""Synthetic valid partial-launch failure remains exportable with strict cleanup."""
import unittest
import test_export_validation as base
from export_validation import export
import rig,launch_journal
from test_launch_journal import owner,receipt
class PartialExportTests(unittest.TestCase):
 def test_valid_failed_partial_export(self):
  fixture=base.ExportTests();fixture.setUp();self.addCleanup(fixture.doCleanups);root=fixture.root
  runtime=rig.read(root/'runtime.json');runtime['launches'].append({'id':'never-started','argv':['java']});rig.write(root/'runtime.json',runtime)
  m=rig.read(root/'manifest.json');m.update(status='failed',launch_journal_version=1,runtime_hash=rig.digest(runtime));m['run_manifest']['runtime_hash']=m['runtime_hash'];m['run_hash']=rig.digest(m['run_manifest']);rig.write(root/'manifest.json',m)
  result=rig.read(root/'evidence/result.json');result.update(status='failed',run_hash=m['run_hash']);rig.write(root/'evidence/result.json',result)
  rig.write(root/'owner.json',owner(1));rig.write(root/'supervisor.json',owner(2))
  j=launch_journal.initialize(root,m,runtime,owner(1),owner(2));j['entries'][0].update(state='spawned',identity=owner(3));j.update(terminal=True,status='failed');rig.write(root/'launch-journal.json',j);rig.write(root/'processes.json',[owner(3)]);receipt(root,j)
  self.assertEqual('fail',export(root,'mods/candidate.jar',[],'controlled')['result'])
if __name__=='__main__':unittest.main()
