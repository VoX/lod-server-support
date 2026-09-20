#!/usr/bin/env python3
"""Import only Prism dependency metadata; never accounts, settings, hooks or worlds."""
import argparse,hashlib,json,sys,urllib.request,urllib.error
from pathlib import Path
from catalog import inspect_jar,require,Invalid
from materialize import nested_metadata,resolution

def import_profile(instance,profile_id,line,platform,route,java,fetch_sources=False):
    instance=Path(instance);pack=json.loads((instance/'mmc-pack.json').read_text())
    artifacts=[];cache={};excluded=[]
    for path in sorted((instance/'minecraft/mods').glob('*.jar')):
        require(not path.is_symlink(),'mod must be a regular file')
        artifact=inspect_jar(path);metadata=artifact['metadata']
        if 'fabric' in metadata and not (platform=='neoforge' and any(k in metadata for k in ('neoforge','forge'))):mid=metadata['fabric']['id'];version=metadata['fabric']['version']
        else:
            mods=[m for dialect,meta in nested_metadata(path,platform) if dialect=='maven' for m in meta.get('mods',[])]
            require(mods,'no native mod identity');mid=mods[0]['modId'];version=mods[0]['version']
        if mid=='lss' or 'fixture' in mid.lower():excluded.append(mid);continue
        url='cache:sha256:'+artifact['sha256']
        if fetch_sources:
            h=hashlib.sha1(path.read_bytes()).hexdigest()
            try:
                with urllib.request.urlopen('https://api.modrinth.com/v2/version_file/'+h+'?algorithm=sha1',timeout=20) as r:data=json.load(r)
                url=next(f['url'] for f in data['files'] if f['hashes']['sha1']==h)
            except (urllib.error.URLError,StopIteration):pass
        artifact.update(id=mid,version=version,source=url,enabled=True,kind='mod');artifacts.append(artifact);cache[artifact['sha256']]=str(path.resolve())
    result={'schema_version':1,'id':profile_id,'line':line,'platform':platform,'route':route,'artifacts':artifacts,
            'components':[{'uid':c['uid'],'version':c['version']} for c in pack['components']]+[{'uid':'java','version':str(java)}],
            'capabilities':[],'status':'unverified','limitations':['Imported dependency candidate only; no final feature validation inferred. Cache-only sources require the exact existing bytes.'],
            'excluded_candidate_or_fixture_ids':excluded}
    return result,cache

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('instance',type=Path)
    for key in ('id','line','platform','route','java','output','cache-output','range-runtime'):p.add_argument('--'+key,required=True)
    p.add_argument('--fetch-source-urls',action='store_true');a=p.parse_args()
    try:
        profile,cache=import_profile(a.instance,a.id,a.line,a.platform,a.route,a.java,a.fetch_source_urls)
        try:result=resolution(profile,cache,json.loads(Path(a.range_runtime).read_text()))
        except Invalid as error:result={'ready':False,'errors':[str(error)],'missing':[]}
        if not result['ready']:profile['status']='blocked';profile['limitations']+=result['errors']+[r['id']+': missing' for r in result['missing']]
        Path(a.output).write_text(json.dumps(profile,indent=2)+'\n')
        target=Path(a.cache_output);target.write_text(json.dumps(cache,indent=2)+'\n');target.chmod(0o600)
        print(json.dumps({'id':profile['id'],'status':profile['status'],'artifacts':len(profile['artifacts']),'errors':result['errors']}))
    except (Invalid,OSError,ValueError,KeyError) as e:p.exit(1,'compat import: '+str(e)+'\n')
if __name__=='__main__':main()
