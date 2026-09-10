import sqlite3,tempfile,unittest,ctypes
from pathlib import Path
from store_witness import observe
class StoreWitnessTests(unittest.TestCase):
 def setUp(self):self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.db=self.root/'store.db'
 def tearDown(self):self.tmp.cleanup()
 def test_actual_committed_row_observation_without_mutation(self):
  self.assertFalse(observe(self.db,self.root,'minecraft:overworld',8,8)['present'])
  with sqlite3.connect(self.db) as c:
   c.execute('CREATE TABLE dims(id INTEGER,name TEXT)');c.execute('INSERT INTO dims VALUES(1,?)',('minecraft:overworld',));c.execute('CREATE TABLE lods_1(pos INTEGER,ts INTEGER,chash INTEGER,usize INTEGER,wirefmt INTEGER,blob BLOB)')
  self.assertFalse(observe(self.db,self.root,'minecraft:overworld',8,8)['present'])
  lib=ctypes.CDLL('libzstd.so.1');lib.ZSTD_compress.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t,ctypes.c_int];lib.ZSTD_compress.restype=ctypes.c_size_t
  encoded=ctypes.create_string_buffer(100);raw=ctypes.create_string_buffer(b'abc');length=lib.ZSTD_compress(encoded,100,raw,3,1)
  with sqlite3.connect(self.db) as c:c.execute('INSERT INTO lods_1 VALUES(?,?,?,?,?,?)',((8<<32)|8,99,0x364b3fb7,3,20,encoded.raw[:length]))
  before=self.db.read_bytes();row=observe(self.db,self.root,'minecraft:overworld',8,8)
  self.assertTrue(row['present']);self.assertEqual(99,row['column_timestamp']);self.assertEqual(before,self.db.read_bytes())
 def test_external_database_rejected(self):
  with self.assertRaises(ValueError):observe('/tmp/foreign-store.db',self.root,'minecraft:overworld',8,8)
 def test_symlink_database_rejected(self):
  link=self.root/'alias';link.symlink_to(self.db)
  with self.assertRaises(ValueError):observe(link,self.root,'minecraft:overworld',8,8)
if __name__=='__main__':unittest.main()
