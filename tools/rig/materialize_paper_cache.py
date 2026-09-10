#!/usr/bin/env python3
"""Reconstruct an owned Paper cache offline from its pinned Paperclip/Mojang jars.
No server boot, world access, dependency download, or Java process. Every output
is checked against Paperclip's own versions/libraries lists.
"""
import argparse
import bz2
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import zipfile


def checksum(data):return hashlib.sha256(data).hexdigest()

def number(data):
    value=int.from_bytes(data,'little')
    return -(value&((1<<63)-1)) if value&(1<<63) else value

def apply_patch(old,patch):
    if patch[:8]!=b'BSDIFF40':raise ValueError('unsupported Paperclip patch')
    controls,diffs,length=(number(patch[i:i+8]) for i in (8,16,24))
    if min(controls,diffs,length)<0:raise ValueError('negative patch section')
    control=bz2.decompress(patch[32:32+controls]);diff=bz2.decompress(patch[32+controls:32+controls+diffs]);extra=bz2.decompress(patch[32+controls+diffs:])
    result=bytearray();oldpos=di=ei=0
    for i in range(0,len(control),24):
        x,y,z=(number(control[i+j:i+j+8]) for j in (0,8,16))
        if min(x,y)<0 or len(result)+x+y>length or di+x>len(diff) or ei+y>len(extra):raise ValueError('invalid patch bounds')
        result.extend((diff[di+j]+(old[oldpos+j] if 0<=oldpos+j<len(old) else 0))&255 for j in range(x))
        result.extend(extra[ei:ei+y]);di+=x;ei+=y;oldpos+=x+z
    if len(result)!=length:raise ValueError('incomplete patch')
    return bytes(result)

def materialize(paper_path,mojang_path,out):
    out.mkdir(parents=True,exist_ok=False,mode=0o700)
    with zipfile.ZipFile(paper_path) as paper,zipfile.ZipFile(mojang_path) as mojang:
        expected,url,cache_name=paper.read('META-INF/download-context').decode().strip().split('\t')
        if checksum(mojang_path.read_bytes())!=expected:raise ValueError('Mojang input differs from Paperclip lock')
        patched={}
        for line in paper.read('META-INF/patches.list').decode().splitlines():
            group,old_hash,patch_hash,new_hash,old_name,patch_name,new_name=line.split('\t')
            old=mojang.read('META-INF/'+group+'/'+old_name);patch=paper.read('META-INF/'+group+'/'+patch_name)
            if checksum(old)!=old_hash or checksum(patch)!=patch_hash:raise ValueError('patch input checksum differs')
            data=apply_patch(old,patch)
            if checksum(data)!=new_hash:raise ValueError('patched output checksum differs')
            patched[group+'/'+new_name]=data
        manifest={}
        for group in ('versions','libraries'):
            for line in paper.read('META-INF/'+group+'.list').decode().splitlines():
                expected,coordinate,name=line.split('\t');relative=group+'/'+name
                if '..' in PurePosixPath(relative).parts or PurePosixPath(relative).is_absolute():raise ValueError('unsafe embedded path')
                embedded='META-INF/'+relative
                data=patched.get(relative)
                if data is None:data=(paper if embedded in paper.namelist() else mojang).read(embedded)
                if checksum(data)!=expected:raise ValueError('dependency output checksum differs: '+relative)
                target=out/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data);manifest[relative]=expected
        (out/'cache').mkdir();shutil.copyfile(mojang_path,out/'cache'/cache_name)
        shutil.copyfile(paper_path,out/'paper.jar')
        manifest['cache/'+cache_name]=checksum(mojang_path.read_bytes());manifest['paper.jar']=checksum(paper_path.read_bytes())
        (out/'closure.json').write_text(json.dumps({'platform':'paper','version':json.loads(paper.read('version.json'))['id'],'files':manifest},indent=2)+'\n')
    return manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--paperclip',required=True);parser.add_argument('--mojang',required=True);parser.add_argument('--output',required=True);args=parser.parse_args()
    result=materialize(Path(args.paperclip),Path(args.mojang),Path(args.output))
    print(json.dumps({'files':len(result),'output':args.output}))
