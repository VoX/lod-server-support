"""Durable JSON publication orders data before rename before directory sync."""
import tempfile,unittest,os
from pathlib import Path
from unittest.mock import patch
import rig
class DurableWriteTests(unittest.TestCase):
 def test_data_sync_precedes_rename_then_directory_sync(self):
  with tempfile.TemporaryDirectory() as d:
   target=Path(d)/'proof.json';events=[];sync=os.fsync;replace=Path.replace
   def observed_sync(fd):
    kind='directory' if Path('/proc/self/fd/'+str(fd)).resolve()==Path(d) else 'file'
    events.append(kind);sync(fd)
   def observed_replace(source,destination):events.append('rename');return replace(source,destination)
   with patch('rig.os.fsync',side_effect=observed_sync),patch.object(Path,'replace',observed_replace):rig.write(target,{'test':'synthetic'})
   self.assertEqual(['file','rename','directory'],events)
   self.assertEqual({'test':'synthetic'},rig.read(target))
 def test_failed_file_sync_never_publishes(self):
  with tempfile.TemporaryDirectory() as d:
   target=Path(d)/'proof.json'
   with patch('rig.os.fsync',side_effect=OSError('synthetic fsync failure')),self.assertRaises(OSError):rig.write(target,{})
   self.assertFalse(target.exists())
 def test_failed_directory_sync_propagates_and_closes_fd(self):
  with tempfile.TemporaryDirectory() as d:
   target=Path(d)/'proof.json';sync=os.fsync;close=os.close;closed=[];count=0
   def fail_second(fd):
    nonlocal count
    count+=1
    if count==2:raise OSError('synthetic directory sync failure')
    sync(fd)
   def observed_close(fd):closed.append(fd);close(fd)
   with patch('rig.os.fsync',side_effect=fail_second),patch('rig.os.close',side_effect=observed_close),self.assertRaises(OSError):rig.write(target,{})
   self.assertTrue(target.exists());self.assertEqual(1,len(closed))
if __name__=='__main__':unittest.main()
