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
    # Sponge facts are CONDITIONAL on the line declaring them (sponge-support-plan.md §2.5):
    # validate recomputes facts() at every pinned commit with THIS code, and pins that
    # predate the Sponge module carry no LINE_*_SPONGE keys.
    sponge = 'LINE_SHIP_SPONGE' in env
    require(not sponge or env['LINE_SHIP_SPONGE'] in ('true','false'), 'invalid Sponge shipping flag')
    platforms = ('fabric','paper','neoforge') + (('sponge',) if sponge else ())
    out = {'line': env['LINE_MC_FABRIC'] if env['LINE_MC_FABRIC'] != '26.1.2' else '26.1',
            'targets': {p: (gradle['minecraft_version'] if p=='fabric' else env['LINE_MC_'+p.upper()]) for p in platforms},
            'java': int(env['LINE_JAVA_VERSION']), 'common_java': 21,
            'mapping': 'official' if int(env['LINE_JAVA_VERSION']) == 25 else 'intermediary',
            'neoforge_shipping': env['LINE_SHIP_NEOFORGE'] == 'true',
            'neoforge_renderer': 'available' if match[1] == 'true' else 'unsupported',
            'paper_loaders': env['LINE_PAPER_LOADERS'].split(),
            'fabric_client_gametests': client_gametests(read('fabric/build.gradle'))}
    if sponge: out['sponge_shipping'] = env['LINE_SHIP_SPONGE'] == 'true'
    return out

def inspect_jar(path):
    path = Path(path)
    require(path.is_file(), f'missing artifact: {path.name}')
    require(zipfile.is_zipfile(path), 'invalid jar archive: '+path.name)
    result = {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'metadata': {}}
    with zipfile.ZipFile(path) as z:
        for name, kind in [('fabric.mod.json','fabric'),('META-INF/neoforge.mods.toml','neoforge'),('META-INF/mods.toml','forge'),
                           ('META-INF/sponge_plugins.json','sponge')]:
            if name in z.namelist():
                info=z.getinfo(name); require(info.file_size <= 2**20, 'oversized mod metadata')
                raw=z.read(name).decode()
                result['metadata'][kind] = json.loads(raw) if kind in ('fabric','sponge') else tomllib.loads(raw)
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
    require(profile['platform'] in ('fabric','paper','neoforge','folia','sponge'), 'unknown platform')
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
            require(not any(k in artifact['metadata'] for k in ('fabric','neoforge','forge','paper','sponge')),'loader artifact mislabeled as library/server')
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

ROLES = ('reference', 'follower', 'frozen')
TIERS = ('full', 'maintained', 'frozen')

def validate_role(root, local):
    """A line's role decides which cross-line file it must carry. A missing file can never turn a
    follower into an unchecked line: the role is written in line.json and must agree."""
    root = Path(root); role = local.get('role')
    require(role in ROLES, 'line.json needs "role": reference, follower or frozen')
    lines = (root/'config/lines/lines.json').exists(); sync = (root/'config/lines/sync.json').exists()
    require(lines == (role == 'reference'), 'config/lines/lines.json belongs on the reference line only')
    require(sync == (role == 'follower'), 'config/lines/sync.json belongs on follower lines, and every follower needs it')
    require((local.get('support_tier') == 'frozen') == (role == 'frozen'), 'support_tier and role disagree about frozen')
    if role == 'reference':
        table = load(root/'config/lines/lines.json')
        require(table.get('schema_version') == 2, 'unsupported lines.json schema')
        require(table.get('reference') == local['facts']['line'], 'lines.json names another reference line')
        require(isinstance(table.get('must_match'), list) and all(isinstance(p, str) and p.endswith('/') for p in table['must_match']), 'must_match must be a list of directory prefixes')
        rows = table.get('lines'); require(isinstance(rows, dict) and table['reference'] in rows, 'lines.json must list every line, the reference included')
        for line, row in rows.items():
            require(set(row) == {'tier', 'branch'} and row['tier'] in TIERS and isinstance(row['branch'], str) and row['branch'], 'invalid lines.json row: ' + line)
        require(rows[table['reference']] == {'tier': 'full', 'branch': 'main'}, 'the reference line is main, tier full')
    return role

def validate(root):
    """This line's own contracts: authored facts equal the build definitions, the role rule, and
    the line-local dependency profiles and validation records. Returns (rows, profiles, records)
    with one row, this line's."""
    root=Path(root); base=root/'config/compatibility'
    local=versioned(load(base/'line.json')); actual=facts(root)
    require(local['facts']==actual,'authored line facts differ from build definitions')
    validate_role(root, local)
    profiles={}
    for path in sorted((base/'profiles').glob('*.json')):
        p=load(path);validate_profile(p,allow_unresolved_ranges=True);require(p['line']==actual['line'],'branch owns only its profiles');require(p['id'] not in profiles,'duplicate profile');profiles[p['id']]=p
    records=[]
    for path in sorted((base/'validation').glob('*.json')):
        r=load(path);validate_record(r,profiles);records.append(r)
    return [(None,local)],profiles,latest(records)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=ROOT)
    sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('validate')
    p=sub.add_parser('inspect');p.add_argument('jar',type=Path)
    args=parser.parse_args()
    try:
        if args.command=='inspect': print(json.dumps(inspect_jar(args.jar),indent=2,sort_keys=True))
        else:
            rows,profiles,records=validate(args.root);print(json.dumps({'lines':len(rows),'profiles':len(profiles),'latest_exact_identity_records':len(records)}))
    except (Invalid,OSError,ValueError,KeyError,zipfile.BadZipFile) as e: parser.exit(1,f'compat: {e}\n')
if __name__=='__main__':main()
