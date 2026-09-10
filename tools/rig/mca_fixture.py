"""Bounded read-only native FULL verification and explicit closed-world hole construction."""
import io,json,struct,zlib,hashlib,shutil
from pathlib import Path
LIMIT=8*1024*1024
HOLES=tuple((i*256-20,-20) for i in range(4))
DOMAIN=tuple((i*256+x,z) for i in range(4) for x in range(-34,35) for z in range(-34,35))

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def regular(path):
    path=Path(path)
    if path.is_symlink() or not path.is_file():raise ValueError('regular owned file required')
    return path

def nbt(data):
    stream=io.BytesIO(data)
    def take(n):
        if not 0<=n<=LIMIT:raise ValueError('NBT length bound')
        value=stream.read(n)
        if len(value)!=n:raise ValueError('truncated NBT')
        return value
    def number(fmt):return struct.unpack('>'+fmt,take(struct.calcsize('>'+fmt)))[0]
    def string():return take(number('H')).decode('utf-8')
    def count():
        value=number('i')
        if not 0<=value<=1_000_000:raise ValueError('NBT collection bound')
        return value
    def value(kind,depth=0):
        if depth>64:raise ValueError('NBT nesting bound')
        if kind in range(1,7):return number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
        if kind==7:return take(count())
        if kind==8:return string()
        if kind==9:
            child=number('B');return [value(child,depth+1) for _ in range(count())]
        if kind==10:
            result={}
            while True:
                child=number('B')
                if child==0:return result
                name=string()
                if name in result:raise ValueError('duplicate NBT compound key')
                result[name]=value(child,depth+1)
        if kind in (11,12):return take(count()*(4 if kind==11 else 8))
        raise ValueError('unknown NBT type')
    kind=number('B');string();result=value(kind)
    if stream.read(1):raise ValueError('trailing NBT bytes')
    return result

def chunk(region,x,z):
    path=Path(region)/f'r.{x>>5}.{z>>5}.mca'
    if not path.exists():return None
    with regular(path).open('rb') as stream:
        stream.seek(4*((x&31)+(z&31)*32));raw=stream.read(4)
        if len(raw)!=4:raise ValueError('truncated MCA header')
        entry=int.from_bytes(raw,'big')
        if entry==0:return None
        offset,sectors=entry>>8,entry&255
        if offset<2 or sectors==0:raise ValueError('invalid MCA location')
        stream.seek(offset*4096);length=int.from_bytes(stream.read(4),'big');kind=stream.read(1)
        if not 1<length<=min(LIMIT,sectors*4096-4) or len(kind)!=1:raise ValueError('MCA payload bound')
        raw=stream.read(length-1)
        if len(raw)!=length-1:raise ValueError('truncated MCA payload')
        if kind in (b'\x01',b'\x02'):
            decoder=zlib.decompressobj(31 if kind==b'\x01' else 15);raw=decoder.decompress(raw,LIMIT+1)
            if len(raw)>LIMIT or not decoder.eof or decoder.unused_data:raise ValueError('compressed MCA bound/trailer')
        elif kind!=b'\x03':raise ValueError('external/unsupported MCA record')
        row=nbt(raw)
        if not isinstance(row,dict):raise ValueError('native chunk compound required')
        return row

def verify_full(world,holes=False):
    world=Path(world);region=world/'dimensions/minecraft/overworld/region';files={};count=0
    for x,z in DOMAIN:
        row=chunk(region,x,z)
        if holes and (x,z) in HOLES:
            if row is not None:raise ValueError('generation hole is present')
            continue
        if row is None or row.get('Status')!='minecraft:full' or (row.get('xPos'),row.get('zPos'))!=(x,z):raise ValueError(f'prefill FULL coordinate absent/mismatched: {x},{z}')
        count+=1
    for path in sorted(region.glob('*.mca')):files[path.name]=sha(regular(path))
    return {'full_chunks':count,'domain_radius':34,'holes':[list(p) for p in HOLES] if holes else [],'region_sha256':files}

def carve(source,destination):
    source=Path(source).resolve();destination=Path(destination).resolve()
    if destination==source or source in destination.parents or destination.exists():raise ValueError('independent new destination required')
    for path in source.rglob('*'):
        if path.is_symlink():raise ValueError('symlink in world input')
        if path.is_file() and ('lss-lod' in path.parts or path.suffix in ('.sqlite','.db')):raise ValueError('prefill world must not contain LSS store/database')
    before=verify_full(source);shutil.copytree(source,destination);changes=[]
    for kind in ('region','entities','poi'):
        folder=destination/'dimensions/minecraft/overworld'/kind
        grouped={}
        for x,z in HOLES:grouped.setdefault(folder/f'r.{x>>5}.{z>>5}.mca',[]).append((x,z))
        for path,positions in grouped.items():
            if not path.exists():
                if kind=='region':raise ValueError('required FULL region absent')
                continue
            regular(path);old=sha(path);edits=[]
            with path.open('r+b') as stream:
                for x,z in positions:
                    offset=4*((x&31)+(z&31)*32)
                    for at in (offset,4096+offset):
                        stream.seek(at);raw=stream.read(4)
                        if len(raw)!=4:raise ValueError('truncated MCA header')
                        edits.append({'offset':at,'before':raw.hex(),'after':'00000000'});stream.seek(at);stream.write(b'\0'*4)
            changes.append({'file':str(path.relative_to(destination)),'before_sha256':old,'after_sha256':sha(path),'edits':edits})
    lock=destination/'session.lock'
    if lock.exists():regular(lock).unlink()
    after=verify_full(destination,holes=True)
    return {'schema_version':1,'operation':'explicit-offline-fixture-hole-construction','before':before,'after':after,'changes':changes}
