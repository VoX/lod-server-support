import struct,zlib,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import mca_fixture as m

def native(x,z,status='minecraft:full'):
    def string(s):b=s.encode();return struct.pack('>H',len(b))+b
    data=b'\x0a\0\0'+b'\x03'+string('xPos')+struct.pack('>i',x)+b'\x03'+string('zPos')+struct.pack('>i',z)+b'\x08'+string('Status')+string(status)+b'\0'
    return zlib.compress(data)

def write(world,x,z,status='minecraft:full'):
    path=world/'dimensions/minecraft/overworld/region'/f'r.{x>>5}.{z>>5}.mca';path.parent.mkdir(parents=True,exist_ok=True)
    data=bytearray(path.read_bytes()) if path.exists() else bytearray(8192)
    payload=native(x,z,status);sector=len(data)//4096;offset=4*((x&31)+(z&31)*32)
    data[offset:offset+4]=struct.pack('>I',(sector<<8)|1);data[4096+offset:4100+offset]=struct.pack('>I',123)
    data+=struct.pack('>I',len(payload)+1)+b'\x02'+payload;data+=b'\0'*((-len(data))%4096);path.write_bytes(data)
    return path

class McaFixtureTest(unittest.TestCase):
    def test_explicit_holes_preserve_full_neighbors_and_input(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'source';points=m.HOLES+((0,0),)
            for x,z in points:write(source,x,z)
            original={str(p.relative_to(source)):m.sha(p) for p in source.rglob('*.mca')}
            with patch.object(m,'DOMAIN',points):report=m.carve(source,root/'derived')
            self.assertEqual(1,report['after']['full_chunks']);self.assertEqual(original,{str(p.relative_to(source)):m.sha(p) for p in source.rglob('*.mca')})
            self.assertEqual(4,len(report['changes']))
    def test_proto_does_not_count_as_native_full(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);write(root,0,0,'minecraft:structure_starts')
            with patch.object(m,'DOMAIN',((0,0),)),self.assertRaisesRegex(ValueError,'FULL'):m.verify_full(root)
    def test_store_contamination_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'input').mkdir();(root/'input/cache.sqlite').write_text('fixture')
            with self.assertRaisesRegex(ValueError,'store'):m.carve(root/'input',root/'output')
    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'input').mkdir();(root/'input/link').symlink_to(root/'input')
            with self.assertRaisesRegex(ValueError,'symlink'):m.carve(root/'input',root/'output')
    def test_truncated_record_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);p=write(root,0,0);p.write_bytes(p.read_bytes()[:8199])
            with self.assertRaisesRegex(ValueError,'truncated'):m.chunk(p.parent,0,0)
    def test_coordinate_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);p=write(root,0,0);data=bytearray(p.read_bytes());payload=native(1,0);data[8192:]=struct.pack('>I',len(payload)+1)+b'\x02'+payload;p.write_bytes(data)
            with patch.object(m,'DOMAIN',((0,0),)),self.assertRaisesRegex(ValueError,'coordinate'):m.verify_full(root)

if __name__=='__main__':unittest.main()
