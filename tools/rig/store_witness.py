"""Read-only committed SQLite row witness. Never writes/imports a store row."""
import hashlib,sqlite3,ctypes
from pathlib import Path

def observe(database,run_root,dimension,x,z):
    database=Path(database);root=Path(run_root).resolve()
    if database.is_symlink() or not database.resolve().is_relative_to(root):raise ValueError('store outside owned run')
    if not database.is_file():return {'present':False,'reason':'database-absent'}
    packed=(x<<32)|(z&0xffffffff)
    with sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True,timeout=1) as db:
        db.execute('PRAGMA query_only=ON')
        dimension_row=db.execute('SELECT id FROM dims WHERE name=?',(dimension,)).fetchone()
        if dimension_row is None:return {'present':False,'reason':'dimension-absent'}
        dim_id=dimension_row[0]
        if type(dim_id) is not int or dim_id<0:raise ValueError('invalid native dimension id')
        row=db.execute(f'SELECT ts,chash,usize,wirefmt,blob FROM lods_{dim_id} WHERE pos=?',(packed,)).fetchone()
        if row is None:return {'present':False,'reason':'row-absent'}
        ts,chash,size,fmt,blob=row
        if size<=0 or size>16*1024*1024 or not isinstance(blob,bytes):raise ValueError('invalid persisted body row')
        codec=ctypes.CDLL('libzstd.so.1');codec.ZSTD_decompress.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t];codec.ZSTD_decompress.restype=ctypes.c_size_t
        output=ctypes.create_string_buffer(size);source=ctypes.create_string_buffer(blob)
        if codec.ZSTD_decompress(output,size,source,len(blob))!=size:raise ValueError('stored zstd body failed independent decompression')
        raw=output.raw;crc=0xffffffff
        for value in raw:
            crc^=value
            for _ in range(8):crc=(crc>>1)^(0x82f63b78 if crc&1 else 0)
        crc^=0xffffffff
        if fmt!=20 or crc!=chash:raise ValueError('stored v20 body content checksum mismatch')
        return dict(present=True,raw_body_sha256=hashlib.sha256(raw).hexdigest(),column_timestamp=ts,content_hash=chash,uncompressed_bytes=size,wire_format=fmt,stored_blob_sha256=hashlib.sha256(blob).hexdigest(),stored_blob_bytes=len(blob))
