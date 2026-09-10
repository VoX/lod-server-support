import copy,hashlib,pathlib,sqlite3,struct,tempfile,unittest,uuid
from prepare_player_starts import validate_player,store_facts
class PlayerStartsTests(unittest.TestCase):
 def row(self):
  world=uuid.UUID('66d934c3-10ee-4b19-a280-63bc5ffae04c').bytes
  player=uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:RigSubjectB').digest(),version=3)
  return {'UUID':player.bytes,'Pos':[4096.5,-60.,.5],'Dimension':'minecraft:overworld','DataVersion':4903,'Health':20.,'DeathTime':0,'WorldUUIDMost':struct.unpack('>q',world[:8])[0],'WorldUUIDLeast':struct.unpack('>q',world[8:])[0]},world
 def test_native_saved_position(self):
  row,world=self.row();self.assertEqual(str(validate_player(row,'RigSubjectB',1,world)),'e4aa4eaa-a5a9-30af-8624-63803d235b5e')
 def test_foreign_uuid(self):
  row,world=self.row();row['UUID']=b'0'*16
  with self.assertRaisesRegex(ValueError,'identity'):validate_player(row,'RigSubjectB',1,world)
 def test_shared_spawn(self):
  row,world=self.row();row['Pos']=[.5,-60.,.5]
  with self.assertRaisesRegex(ValueError,'position'):validate_player(row,'RigSubjectB',1,world)
 def test_foreign_world(self):
  row,world=self.row();row['WorldUUIDMost']+=1
  with self.assertRaisesRegex(ValueError,'world'):validate_player(row,'RigSubjectB',1,world)
 def test_dead_player(self):
  row,world=self.row();row['Health']=0
  with self.assertRaisesRegex(ValueError,'alive'):validate_player(row,'RigSubjectB',1,world)
 def test_store_initial_premises_and_no_sidecars(self):
  with tempfile.TemporaryDirectory() as directory:
   root=pathlib.Path(directory);db=root/'world/lss-lod/store.db';db.parent.mkdir(parents=True)
   with sqlite3.connect(db) as c:
    c.executescript("create table dims(id integer,name text);insert into dims values(1,'minecraft:overworld');create table lods_1(pos integer);")
    for i in range(4):c.execute('insert into lods_1 values(?)',(((i*256-8)<<32)|8,))
   self.assertEqual(12,len(store_facts(root)));self.assertEqual([db],list(db.parent.iterdir()))
   with sqlite3.connect(db) as c:c.execute('insert into lods_1 values(?)',((8<<32)|0xfffffff8,))
   with self.assertRaisesRegex(ValueError,'contamination'):store_facts(root)
