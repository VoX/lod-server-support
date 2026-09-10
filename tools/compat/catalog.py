#!/usr/bin/env python3
"""Offline compatibility authority. No network access or implicit artifact substitution."""
from __future__ import annotations
import argparse, hashlib, json, re, subprocess, sys, tomllib, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = 1
class Invalid(ValueError): pass
class UnsupportedRange(Invalid): pass

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()

def digest(value): return hashlib.sha256(canonical(value)).hexdigest()
def load(path): return json.loads(Path(path).read_text())
def require(condition, message):
    if not condition: raise Invalid(message)
def versioned(value):
    require(value.get('schema_version') == SCHEMA, 'unsupported schema_version')
    return value

def git(root, *args):
    p = subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True)
    require(p.returncode == 0, p.stderr.strip() or 'git command failed')
    return p.stdout

def read_ref(root, ref, path):
    require(bool(re.fullmatch(r'[0-9a-f]{40}', ref)), 'source ref must be a full commit SHA')
    return git(root, 'show', f'{ref}:{path}')

def properties(text):
    return dict(line.split('=', 1) for raw in text.splitlines() if (line := raw.strip()) and not line.startswith('#') and '=' in line)

def client_gametests(build):
    # Loom registers runClientGameTest from this setting; the task name need
    # not occur in the source build script at all. Resolve only the two actual
    # declared flavors: literal boolean, or one unreassigned top-level literal.
    active=re.sub(r'/\*.*?\*/','',build,flags=re.S)
    active=re.sub(r'(?m)//[^\n]*','',active)
    values=re.findall(r'(?m)^\s*enableClientGameTests\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\s*$',active)
    require(len(values)==1,'client gametest capability requires one explicit Loom setting')
    value=values[0]
    if value not in ('true','false'):
        declarations=re.findall(r'(?m)^def '+re.escape(value)+r'\s*=\s*(true|false)\s*$',active)
        assignments=re.findall(r'\b'+re.escape(value)+r'\s*[+*/%&|^-]?=(?!=)',active)
        require(len(declarations)==len(assignments)==1,'client gametest variable must be one unreassigned top-level boolean')
        value=declarations[0]
    return value=='true'

def facts(root, ref=None):
    def read(path): return read_ref(root, ref, path) if ref else (Path(root)/path).read_text()
    env = properties(read('.github/line.env')); gradle = properties(read('gradle.properties'))
    require(env['LINE_MC_FABRIC'] == gradle['minecraft_version'] or (env['LINE_MC_FABRIC']=='26.1' and gradle['minecraft_version']=='26.1.2'), 'line.env / Gradle MC mismatch')
    require(env['LINE_SHIP_NEOFORGE'] in ('true','false'), 'invalid shipping flag')
    renderer = read('neoforge/src/main/java/dev/vox/lss/networking/client/FarPlayerRenderer.java')
    match = re.search(r'RENDER_AVAILABLE\s*=\s*(true|false)', renderer)
    require(match is not None, 'renderer availability constant absent')
    return {'line': env['LINE_MC_FABRIC'] if env['LINE_MC_FABRIC'] != '26.1.2' else '26.1',
            'targets': {p: (gradle['minecraft_version'] if p=='fabric' else env['LINE_MC_'+p.upper()]) for p in ('fabric','paper','neoforge')},
            'java': int(env['LINE_JAVA_VERSION']), 'common_java': 21,
            'mapping': 'official' if int(env['LINE_JAVA_VERSION']) == 25 else 'intermediary',
            'neoforge_shipping': env['LINE_SHIP_NEOFORGE'] == 'true',
            'neoforge_renderer': 'available' if match[1] == 'true' else 'unsupported',
            'paper_loaders': env['LINE_PAPER_LOADERS'].split(),
            'fabric_client_gametests': client_gametests(read('fabric/build.gradle'))}

def inspect_jar(path):
    path = Path(path)
    require(path.is_file(), f'missing artifact: {path.name}')
    require(zipfile.is_zipfile(path), 'invalid jar archive: '+path.name)
    result = {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'metadata': {}}
    with zipfile.ZipFile(path) as z:
        for name, kind in [('fabric.mod.json','fabric'),('META-INF/neoforge.mods.toml','neoforge'),('META-INF/mods.toml','forge')]:
            if name in z.namelist():
                info=z.getinfo(name); require(info.file_size <= 2**20, 'oversized mod metadata')
                raw=z.read(name).decode()
                result['metadata'][kind] = json.loads(raw) if kind == 'fabric' else tomllib.loads(raw)
        if 'plugin.yml' in z.namelist():
            require(z.getinfo('plugin.yml').file_size<=2**20,'oversized plugin metadata')
            import yaml
            raw=z.read('plugin.yml').decode()
            try:
                require(not any(isinstance(token,(yaml.tokens.AliasToken,yaml.tokens.AnchorToken)) for token in yaml.scan(raw)),'plugin YAML aliases require explicit review')
                metadata=yaml.safe_load(raw)
            except yaml.YAMLError as error:raise Invalid('invalid plugin YAML metadata') from error
            require(isinstance(metadata,dict),'plugin metadata must be a mapping')
            result['metadata']['paper']=metadata
        if 'META-INF/jarjar/metadata.json' in z.namelist():
            result['metadata']['jarjar'] = json.loads(z.read('META-INF/jarjar/metadata.json'))
    require(result['metadata'], f'no recognized mod metadata in {path.name}')
    return result

def numeric(version):
    # Deliberately narrow: unsupported range syntax is review-required, never green.
    if not re.fullmatch(r'\d+(?:\.\d+)*', version): raise UnsupportedRange(f'unsupported version syntax: {version}')
    return tuple(map(int,version.split('.')))

def compare(a,b):
    a,b=numeric(a),numeric(b); n=max(len(a),len(b));a+= (0,)*(n-len(a)); b+=(0,)*(n-len(b)); return (a>b)-(a<b)

def accepts(version, constraint, dialect='fabric'):
    if isinstance(constraint,list): return any(accepts(version,c,dialect) for c in constraint)
    require(isinstance(constraint,str),'dependency constraint must be string/list')
    if constraint == '*': return True
    if dialect == 'maven':
        m=re.fullmatch(r'([\[(])([^,]*),([^\])]*)([\])])',constraint)
        if m:
            return (not m[2] or compare(version,m[2]) >= (0 if m[1]=='[' else 1)) and (not m[3] or compare(version,m[3]) <= (0 if m[4]==']' else -1))
        m=re.fullmatch(r'\[([^,]+)\]',constraint)
        require(m is not None, 'unsupported Maven range: '+constraint)
        return version == m[1]
    terms=constraint.split()
    for term in terms:
        if term.startswith('~'):
            # Fabric SAME_TO_NEXT_MINOR: same major/minor and >= lower bound.
            bound=term[1:].removesuffix('-')
            a,b=numeric(version),numeric(bound);a+=(0,)*(max(2,len(a))-len(a));b+=(0,)*(max(2,len(b))-len(b))
            if a[:2]!=b[:2] or compare(version,bound)<0: return False
            continue
        if term == version: continue
        m=re.fullmatch(r'(>=|<=|>|<|=)?(\d+(?:\.\d+)*)(-)?',term)
        if m is None: raise UnsupportedRange('unsupported Fabric range (review required): '+term)
        op=m[1] or '='; c=compare(version,m[2])
        if c==0 and m[3]: c=1  # numeric release is later than the empty prerelease floor
        if not {'=':c==0,'>':c>0,'<':c<0,'>=':c>=0,'<=':c<=0}[op]: return False
    return bool(terms)

def validate_profile(profile,allow_unresolved_ranges=False):
    versioned(profile)
    for key in ('id','line','platform','route','artifacts','components','capabilities','status','limitations'):
        require(key in profile, 'profile missing '+key)
    require(profile['platform'] in ('fabric','paper','neoforge','folia'), 'unknown platform')
    require(profile['route'] in ('native','connector'), 'unknown loader route')
    require(profile['route'] != 'connector' or profile['platform']=='neoforge','Connector requires NeoForge')
    require(profile['status'] in ('unverified','blocked','unsupported'),'profile is a lock, not a validation pass')
    ids=set()
    for artifact in profile['artifacts']:
        for key in ('id','version','file','sha256','source','metadata','enabled'): require(key in artifact,'artifact missing '+key)
        require(re.fullmatch(r'[0-9a-f]{64}',artifact['sha256']) is not None,'missing/invalid artifact SHA256')
        require(Path(artifact['file']).name == artifact['file'],'artifact filename must be basename')
        require(artifact['source'] and not artifact['source'].startswith('/'),'source must not be a personal path')
        require(artifact['id'] not in ids,'duplicate artifact id: '+artifact['id']);ids.add(artifact['id'])
        require(isinstance(artifact['enabled'],bool),'enabled must be boolean')
        require(artifact.get('kind','mod') in ('mod','plugin','library','server'),'unknown artifact kind')
        if not artifact['enabled']: continue
        if artifact.get('kind','mod') in ('library','server'):
            require(not any(k in artifact['metadata'] for k in ('fabric','neoforge','forge','paper')),'loader artifact mislabeled as library/server')
        fabric=artifact['metadata'].get('fabric')
        if fabric and not (profile['platform']=='neoforge' and any(k in artifact['metadata'] for k in ('neoforge','forge'))):
            require(profile['platform']=='fabric' or profile['route']=='connector' or (profile['status']=='blocked' and bool(profile['limitations'])),'Fabric jar on native non-Fabric profile')
            mc=next((c['version'] for c in profile['components'] if c['uid']=='net.minecraft'),profile['line'])
            constraint=fabric.get('depends',{}).get('minecraft','*')
            try: require(accepts(mc,constraint),'wrong MC metadata: '+artifact['file'])
            except UnsupportedRange:
                if not allow_unresolved_ranges and profile['status'] != 'blocked': raise
            except Invalid:
                if profile['status'] != 'blocked': raise
                require(bool(profile['limitations']),'blocked metadata must explain limitation')
    require(profile['status'] != 'blocked' or bool(profile['limitations']),'blocked profile lacks reason')
    return digest(profile)

def validate_record(record, profiles):
    versioned(record)
    for key in ('run_id','timestamp','profile_id','profile_hash','run_manifest','run_hash','scenario','feature','result','evidence_sha256','limitations'):
        require(key in record,'validation missing '+key)
    for key in ('run_id','timestamp','profile_id','scenario','feature'):
        require(isinstance(record[key],str) and bool(record[key].strip()),'validation identity must be nonempty text: '+key)
    require(isinstance(record['limitations'],list) and all(isinstance(value,str) for value in record['limitations']),'validation limitations must be text entries')
    require(record['profile_id'] in profiles,'unknown validation profile')
    require(record['profile_hash']==digest(profiles[record['profile_id']]),'stale profile evidence')
    require(not any(k in record['run_manifest'] for k in ('run_id','timestamp','evidence_path','evidence_index','output_directory')), 'ephemeral fields cannot change reusable run identity')
    require(record['run_hash']==digest(record['run_manifest']),'stale run evidence')
    for key in ('candidate_sha256','fixture_sha256','checker_sha256','world_sha256','effective_config','jvm_flags','backend'):
        require(key in record['run_manifest'],'run identity missing '+key)
    for key in ('candidate_sha256','fixture_sha256','checker_sha256','world_sha256'):
        require(re.fullmatch('[0-9a-f]{64}',record['run_manifest'][key]) is not None,'invalid '+key)
    require(re.fullmatch('[0-9a-f]{64}',record['evidence_sha256']) is not None,'invalid evidence digest')
    if 'evidence_index' in record:
        require(isinstance(record['evidence_index'],dict) and bool(record['evidence_index']),'evidence index must be nonempty mapping')
        require(all(isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value) for value in record['evidence_index'].values()),'invalid evidence artifact digest')
        require(digest(record['evidence_index'])==record['evidence_sha256'],'evidence index digest differs')
    if record['scenario']=='server-smoke':
        from server_smoke_identity import validate_binding
        validate_binding(record,profiles,require)
    require(record['result'] in ('pass','fail','inconclusive','rejected-optional'),'invalid validation result')
    require(re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ',record['timestamp']) is not None,'timestamp must be canonical UTC')
    from datetime import datetime
    try: datetime.strptime(record['timestamp'],'%Y-%m-%dT%H:%M:%SZ')
    except ValueError: raise Invalid('timestamp must name a real UTC date/time')

def latest(records):
    result={};attempts=set()
    for r in records:
        attempt=(r['profile_hash'],r['scenario'],r['feature'],r['run_id'])
        require(attempt not in attempts,'duplicate validation attempt identity')
        attempts.add(attempt)
        key=(r['profile_hash'],r['run_hash'],r['scenario'],r['feature'])
        if key not in result or (r['timestamp'],r['run_id']) > (result[key]['timestamp'],result[key]['run_id']): result[key]=r
    return list(result.values())

def validate(root):
    root=Path(root); base=root/'config/compatibility'
    local=versioned(load(base/'line.json')); actual=facts(root)
    require(local['facts']==actual,'authored line facts differ from build definitions')
    profiles={}
    for path in sorted((base/'profiles').glob('*.json')):
        p=load(path);validate_profile(p,allow_unresolved_ranges=True);require(p['line']==actual['line'],'branch owns only its profiles');require(p['id'] not in profiles,'duplicate profile');profiles[p['id']]=p
    records=[]
    for path in sorted((base/'validation').glob('*.json')):
        r=load(path);validate_record(r,profiles);records.append(r)
    refs=versioned(load(base/'source-refs.json'))
    require(set(refs['sources'])=={'1.21.1','1.21.10','1.21.11','26.1','26.2'},'source set must contain five lines')
    rows=[]
    for line, source in sorted(refs['sources'].items()):
        require(source['schema_version']==1,'unsupported source schema')
        row=versioned(json.loads(read_ref(root,source['commit'],'config/compatibility/line.json')))
        require(row['facts']==facts(root,source['commit']),'pinned line data/build mismatch')
        require(row['facts']['line']==line,'source line identity mismatch');rows.append((source['commit'],row))
    return rows,profiles,latest(records)

def snapshot_validation_records(root,rows):
    """Aggregate only fixed snapshot evidence, never a moving sibling checkout."""
    result=[]
    for commit,row in rows:
        line=row['facts']['line'];profiles={};records=[]
        paths=git(root,'ls-tree','-r','--name-only',commit,'--','config/compatibility/profiles','config/compatibility/validation').splitlines()
        for path in sorted(paths):
            if not path.endswith('.json') or not path.startswith('config/compatibility/profiles/'):continue
            profile=json.loads(read_ref(root,commit,path));validate_profile(profile,allow_unresolved_ranges=True)
            require(profile['line']==line and profile['id'] not in profiles,'pinned profile owner/identity mismatch')
            profiles[profile['id']]=profile
        for path in sorted(paths):
            if not path.endswith('.json') or not path.startswith('config/compatibility/validation/'):continue
            record=json.loads(read_ref(root,commit,path));validate_record(record,profiles);records.append(record)
        result.extend((line,commit,record) for record in latest(records))
    return sorted(result,key=lambda item:(item[0],item[2]['profile_id'],item[2]['scenario'],item[2]['feature'],item[2]['timestamp'],item[2]['run_id']))

def render(root,check=False):
    root=Path(root);rows,profiles,records=validate(root)
    out=['<!-- Generated by tools/compat/catalog.py render; do not edit. -->','# Compatibility','',
         'This snapshot describes build capabilities. A capability is not live validation. Each source is pinned below; historical release notes remain dated records.','',
         '| Line | Fabric / Paper / Neo targets | Java (common) | Neo shipped | Neo far renderer | Paper loaders | Client gametests | Source |',
         '| --- | --- | --- | --- | --- | --- | --- | --- |']
    for sha,row in rows:
        f=row['facts'];out.append(f"| {f['line']} | {' / '.join(f['targets'].values())} | {f['java']} (21) | {str(f['neoforge_shipping']).lower()} | {f['neoforge_renderer']} | {', '.join(f['paper_loaders'])} | {f['fabric_client_gametests']} | `{sha}` |")
    out+=['','Dependency locks and feature-specific validation records are line-local under `config/compatibility/`. Installed jars are unverified until an exact run identity has accepted evidence. Blocked dependency profiles cannot be launched as validated profiles.','']
    attempts=snapshot_validation_records(root,rows)
    out+=['## Recorded feature attempts','',
          'These records come only from the fixed source snapshots above. A pass applies to the listed exact profile, candidate and reusable run inputs; it does not certify a newer build or another feature. Each distinct input identity retains its latest attempt, including failures. Full hashes and evidence indexes are in the source snapshot’s `config/compatibility/validation/` records.','']
    if attempts:
        out+=['| Line / profile | Scenario / feature | UTC date | Result | Candidate SHA256 | Profile / run SHA256 | Limitations |',
              '| --- | --- | --- | --- | --- | --- | --- |']
        def cell(value):return str(value).replace('|','&#124;').replace('\n',' ')
        for line,commit,record in attempts:
            values=(line+' / '+record['profile_id'],record['scenario']+' / '+record['feature'],record['timestamp'],record['result'],
                    record['run_manifest']['candidate_sha256'],record['profile_hash']+' / '+record['run_hash'],'; '.join(record['limitations']) or 'None recorded')
            out.append('| '+' | '.join(cell(value) for value in values)+' |')
    else:out+=['No feature attempts are recorded in this pinned snapshot; live validation remains unverified.']
    out+=['']
    content='\n'.join(out)
    path=root/'docs/compatibility.md'
    if check: require(path.exists() and path.read_text()==content,'generated compatibility documentation is stale')
    else: path.write_text(content)
    readme=root/'README.md'; text=readme.read_text()
    matrix_start='<!-- LSS SERVER MATRIX START -->';matrix_end='<!-- LSS SERVER MATRIX END -->'
    matrix=[matrix_start,'| Minecraft line | Fabric / Paper | Folia | NeoForge shipping | Neo far renderer |','| --- | --- | --- | --- | --- |']
    for _,row in rows:
        f=row['facts'];matrix.append(f"| {f['line']} | maintained | {'experimental' if 'folia' in f['paper_loaders'] else 'unsupported'} | {'shipped; best-effort' if f['neoforge_shipping'] else 'maintained build only'} | {f['neoforge_renderer']} |")
    matrix += [matrix_end]
    block='\n'.join(matrix)
    if matrix_start in text: text=re.sub(re.escape(matrix_start)+r'.*?'+re.escape(matrix_end),lambda _:block,text,flags=re.S)
    else: text=re.sub(r'\| Minecraft \| Fabric \| Paper / Purpur \| Folia \| NeoForge \|\n(?:\|[^\n]*\n)+',lambda _:block+'\n',text,count=1)
    require(matrix_start in text,'README compatibility matrix anchor absent')
    start='<!-- LSS COMPATIBILITY START -->';end='<!-- LSS COMPATIBILITY END -->'
    section=start+'\nCurrent platform, shipping and renderer facts: [compatibility matrix](docs/compatibility.md). Dependency locks describe candidates; dated feature evidence establishes tested combinations.\n'+end
    new=re.sub(re.escape(start)+r'.*?'+re.escape(end),lambda _:section,text,flags=re.S) if start in text else text.rstrip()+'\n\n'+section+'\n'
    if check: require(new==readme.read_text(),'generated README compatibility section is stale')
    else: readme.write_text(new)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=ROOT)
    sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('validate'); p=sub.add_parser('render');p.add_argument('--check',action='store_true')
    p=sub.add_parser('inspect');p.add_argument('jar',type=Path)
    args=parser.parse_args()
    try:
        if args.command=='inspect': print(json.dumps(inspect_jar(args.jar),indent=2,sort_keys=True))
        elif args.command=='render': render(args.root,args.check)
        else:
            rows,profiles,records=validate(args.root);print(json.dumps({'lines':len(rows),'profiles':len(profiles),'latest_exact_identity_records':len(records)}))
    except (Invalid,OSError,ValueError,KeyError,zipfile.BadZipFile) as e: parser.exit(1,f'compat: {e}\n')
if __name__=='__main__':main()
