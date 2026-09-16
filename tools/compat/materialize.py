#!/usr/bin/env python3
"""Resolve exact artifact locks from a configured cache; fetch is a separate command."""
import argparse,hashlib,io,json,os,re,sys,tempfile,tomllib,urllib.request,zipfile
from pathlib import Path
from catalog import Invalid,require,load,validate_profile,inspect_jar,accepts,digest

def nested_metadata(path,platform=None):
    """Only loader-declared nested jars count; unrelated archives do not provide mods."""
    result=[];budget=[128*1024*1024]
    def visit(data,depth=0):
        require(depth<=8,'nested jar depth exceeds bound')
        with zipfile.ZipFile(data) as z:
            names=z.namelist();nested=[]
            if 'fabric.mod.json' in names and not (platform=='neoforge' and any(n in names for n in ('META-INF/neoforge.mods.toml','META-INF/mods.toml'))):
                require(z.getinfo('fabric.mod.json').file_size<=2**20,'oversized metadata')
                m=json.loads(z.read('fabric.mod.json'));result.append(('fabric',m))
                nested.extend(row['file'] for row in m.get('jars',[]))
            for name in (('META-INF/neoforge.mods.toml','META-INF/mods.toml') if platform in (None,'neoforge') else ()):
                if name in names:
                    require(z.getinfo(name).file_size<=2**20,'oversized metadata')
                    m=tomllib.loads(z.read(name).decode())
                    # Loader's ${file.jarVersion} comes from the jar manifest.
                    manifest=z.read('META-INF/MANIFEST.MF').decode() if 'META-INF/MANIFEST.MF' in names else ''
                    import re
                    version=re.search(r'^Implementation-Version:\s*(.+)$',manifest,re.M)
                    for mod in m.get('mods',[]):
                        if mod.get('version')=='${file.jarVersion}' and version:mod['version']=version[1].strip()
                    result.append(('maven',m))
            if platform in (None,'neoforge') and 'META-INF/jarjar/metadata.json' in names:
                nested.extend(row['path'] for row in json.loads(z.read('META-INF/jarjar/metadata.json')).get('jars',[]))
            for name in dict.fromkeys(nested):
                require(name in names,'declared nested jar missing')
                budget[0]-=z.getinfo(name).file_size;require(budget[0]>=0,'nested jar byte budget exceeded')
                visit(io.BytesIO(z.read(name)),depth+1)
    visit(path);return result

def resolution(profile,cache,range_runtime=None):
    validate_profile(profile,allow_unresolved_ranges=range_runtime is not None);missing=[];resolved=[];errors=[]
    for a in profile['artifacts']:
        if not a['enabled']:continue
        path=cache.get(a['sha256'])
        if not path or not Path(path).is_file():missing.append({'id':a['id'],'sha256':a['sha256'],'source':a['source']});continue
        path=Path(path)
        if path.is_symlink():errors.append(a['id']+': symlink cache entry');continue
        with path.open('rb') as stream: actual_hash=hashlib.file_digest(stream,'sha256').hexdigest()
        if actual_hash!=a['sha256']:errors.append(a['id']+': hash mismatch');continue
        resolved.append((a,path))
    provided={};deps=[];conflicts=[];top_level_ids=set()
    component_ids={'net.minecraft':'minecraft','net.fabricmc.fabric-loader':'fabricloader','net.neoforged':'neoforge','java':'java'}
    for c in profile['components']:
        if c['uid'] in component_ids:provided[component_ids[c['uid']]]={c['version']}
    for a,path in resolved:
        if a.get('kind','mod') in ('library','server'):
            try:
                with zipfile.ZipFile(path) as archive:
                    if any(name in archive.namelist() for name in ('fabric.mod.json','META-INF/neoforge.mods.toml','META-INF/mods.toml','plugin.yml')):
                        errors.append(a['id']+': loader artifact mislabeled as library/server')
            except zipfile.BadZipFile:errors.append(a['id']+': invalid library/server jar archive')
            continue
        try:
            current=inspect_jar(path)['metadata']
            # Historical inventories may contain abbreviated metadata, so exact runtime
            # locks require full metadata equality before admission.
            require(current==a['metadata'],a['id']+': embedded metadata differs from lock')
            if profile['platform'] in ('paper','folia'):
                require('paper' in current,'Paper platform requires plugin.yml')
                meta=current['paper'];name=meta.get('name');version=str(meta.get('version',''))
                require(isinstance(name,str) and name and version and '${' not in version,'invalid/unresolved plugin identity')
                require(name not in top_level_ids,'duplicate top-level plugin identity');top_level_ids.add(name)
                if profile['platform']=='folia':require(meta.get('folia-supported') is True,name+': Folia opt-in missing')
                mc=next((c['version'] for c in profile['components'] if c['uid']=='net.minecraft'),profile['line'])
                if 'api-version' in meta:
                    from catalog import compare
                    require(compare(mc,str(meta['api-version']))>=0,name+': plugin API newer than server')
                for key in ('depend','softdepend','loadbefore','provides'):
                    require(isinstance(meta.get(key,[]),list) and all(isinstance(v,str) and v for v in meta.get(key,[])),'invalid plugin '+key)
                for mid in [name,*meta.get('provides',[])]:provided.setdefault(mid,set()).add(version)
                deps.extend((name,dep,'*','maven') for dep in meta.get('depend',[]))
                continue
            require(a.get('kind','mod')!='plugin','plugin artifact missing plugin.yml')
            require(('fabric' in current if profile['platform']=='fabric' else
                     any(k in current for k in ('neoforge','forge')) or
                     profile['route']=='connector' and 'fabric' in current),
                    'artifact lacks a descriptor for the selected loader route')
            top_ids=([current['fabric']['id']] if 'fabric' in current and not (profile['platform']=='neoforge' and any(k in current for k in ('neoforge','forge'))) else [mod['modId'] for kind in ('neoforge','forge') for mod in current.get(kind,{}).get('mods',[])])
            require(not top_level_ids.intersection(top_ids),'duplicate top-level mod identity')
            top_level_ids.update(top_ids)
            if profile['route']=='connector':
                # Connector bundles its Fabric loader. Its own manifest is the
                # version authority, not a synthetic dependency exception.
                with zipfile.ZipFile(path) as archive:
                    manifest=archive.read('META-INF/MANIFEST.MF').decode() if 'META-INF/MANIFEST.MF' in archive.namelist() else ''
                    bundled=re.search(r'^Fabric-Loader-Version:\s*([^\r\n]+)',manifest,re.M)
                    if bundled and 'net/fabricmc/loader/impl/FabricLoaderImpl.class' in archive.namelist():
                        provided.setdefault('fabricloader',set()).add(bundled[1].strip())
            for dialect,m in nested_metadata(path,profile['platform']):
                if dialect=='fabric':
                    require(profile['platform']=='fabric' or profile['route']=='connector','Fabric metadata on native Neo route')
                    mods=[(m['id'],m['version'])]+[(alias,m['version']) for alias in m.get('provides',[])]
                    deps.extend((m['id'],k,v,dialect) for k,v in m.get('depends',{}).items())
                    conflicts.extend((m['id'],k,v,dialect) for k,v in m.get('breaks',{}).items())
                else:
                    mods=[(mid,row['version']) for row in m.get('mods',[]) for mid in [row['modId'],*row.get('provides',[])]]
                    for owner,rows in m.get('dependencies',{}).items():
                        deps.extend((owner,row['modId'],row.get('versionRange','*'),dialect) for row in rows if row.get('type','required')=='required' and row.get('mandatory',True))
                        conflicts.extend((owner,row['modId'],row.get('versionRange','*'),dialect) for row in rows if row.get('type')=='incompatible')
                for mid,version in mods:
                    require('${' not in version,'unresolved mod version '+mid)
                    provided.setdefault(mid,set()).add(version)
        except (Invalid,ValueError,KeyError,zipfile.BadZipFile) as e:errors.append(str(e))
    real_results={}
    if range_runtime:
        from loader_ranges import evaluate
        queries=[(version,term,dialect) for owner,dep,constraint,dialect in deps+conflicts if dep in provided for version in provided[dep] for term in (constraint if isinstance(constraint,list) else [constraint])]
        real_results=dict(zip(queries,evaluate(queries,range_runtime)))
    # Nested libraries may offer multiple versions. Require a jointly compatible
    # candidate for every recorded constraint, including nested alternatives;
    # complex graphs may be rejected conservatively, never blessed by per-edge
    # incompatible choices. The real loader remains the launch acceptance gate.
    candidates={mid:set(versions) for mid,versions in provided.items()}
    for owner,dep,constraint,dialect in deps:
        if dep not in provided:errors.append(f'{owner}: missing dependency {dep} {constraint}');continue
        try:
            accepted_versions=set()
            for version in candidates[dep]:
                if range_runtime:
                    outcomes=[real_results[(version,term,dialect)] for term in (constraint if isinstance(constraint,list) else [constraint])]
                    require('unsupported' not in outcomes,'actual loader rejected range syntax')
                    accepted='accepted' in outcomes
                else: accepted=accepts(version,constraint,dialect)
                if accepted:accepted_versions.add(version)
            require(accepted_versions,f'{owner}: incompatible dependency {dep} {constraint}')
            candidates[dep]=accepted_versions
        except Invalid as e:errors.append(str(e))
    for owner,dep,constraint,dialect in conflicts:
        if dep not in candidates:continue
        try:
            allowed=set()
            for version in candidates[dep]:
                if range_runtime:
                    outcomes=[real_results[(version,term,dialect)] for term in (constraint if isinstance(constraint,list) else [constraint])]
                    require('unsupported' not in outcomes,'actual loader rejected conflict range syntax')
                    incompatible='accepted' in outcomes
                else:incompatible=accepts(version,constraint,dialect)
                if not incompatible:allowed.add(version)
            require(allowed,f'{owner}: incompatible installed mod {dep} {constraint}')
            candidates[dep]=allowed
        except Invalid as e:errors.append(str(e))
    if profile['route']=='connector' and not any('connector' in key for key in provided):errors.append('Connector route lacks Connector mod')
    return {'schema_version':1,'profile_hash':digest(profile),'missing':missing,'errors':sorted(set(errors)),'ready':not missing and not errors and profile['status']=='unverified','resolved_artifacts':len(resolved)}

def fetch(profile,directory):
    """Explicit acquisition. No credentials, substitutions or cache admission before hash."""
    validate_profile(profile,allow_unresolved_ranges=True);directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    result={}
    for a in profile['artifacts']:
        if not a['enabled']:continue
        target=directory/a['sha256']
        require(not target.is_symlink(),'cache target cannot be symlink')
        if target.exists():
            with target.open('rb') as stream:require(hashlib.file_digest(stream,'sha256').hexdigest()==a['sha256'],'existing cache hash mismatch')
            result[a['sha256']]=str(target);continue
        url=a['source'];require(url.startswith('https://'),'artifact lacks HTTPS source; supply exact cache entry: '+a['id'])
        fd,tmp=tempfile.mkstemp(prefix='.fetch-',dir=directory)
        try:
            with os.fdopen(fd,'wb') as out,urllib.request.urlopen(url,timeout=30) as incoming:
                require(incoming.url.startswith('https://'),'redirect downgraded transport');count=0
                while chunk:=incoming.read(1024*1024):
                    count+=len(chunk);require(count<=512*1024*1024,'artifact exceeds download budget');out.write(chunk)
            with open(tmp,'rb') as stream:require(hashlib.file_digest(stream,'sha256').hexdigest()==a['sha256'],'downloaded SHA256 mismatch: '+a['id'])
            os.replace(tmp,target);result[a['sha256']]=str(target)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);s=p.add_subparsers(dest='command',required=True)
    r=s.add_parser('resolve');r.add_argument('profile',type=Path);r.add_argument('cache',type=Path);r.add_argument('--range-runtime',type=Path)
    f=s.add_parser('fetch');f.add_argument('profile',type=Path);f.add_argument('directory',type=Path)
    a=p.parse_args()
    try:
        result=resolution(load(a.profile),load(a.cache),load(a.range_runtime) if a.range_runtime else None) if a.command=='resolve' else fetch(load(a.profile),a.directory)
        print(json.dumps(result,indent=2,sort_keys=True))
        if a.command=='resolve' and not result['ready']:sys.exit(1)
    except (Invalid,OSError,ValueError,KeyError) as e:p.exit(1,f'compat materialize: {e}\n')
if __name__=='__main__':main()
