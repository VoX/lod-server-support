#!/usr/bin/env python3
"""Explicit-ref inventory and conservative isolated cherry-pick preparation."""
from __future__ import annotations
import argparse, fnmatch, hashlib, json, os, re, subprocess, sys, tempfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from catalog import Invalid, require, git, load, versioned, read_ref
ROOT=Path(__file__).resolve().parents[2]

def files(root,ref): return git(root,'ls-tree','-r','--name-only',ref).splitlines()
def sha(root,ref):
    value=git(root,'rev-parse','--verify',ref+'^{commit}').strip()
    require(len(value)==40,'invalid commit'); return value

def classify(manifest,path):
    if path in manifest['files']: return manifest['files'][path]
    # New production files must be explicitly classified, not silently copied.
    for pattern,kind in manifest.get('nonproduction_patterns',{}).items():
        if fnmatch.fnmatchcase(path,pattern): return {'kind':kind}
    return {'kind':'unclassified'}

def validate_manifest(manifest):
    versioned(manifest)
    require(isinstance(manifest.get('files'),dict),'classification files must be explicit mapping')
    for path,rule in manifest['files'].items():
        require(not Path(path).is_absolute() and '..' not in Path(path).parts,'invalid classified path')
        kind=rule.get('kind');require(kind in ('identical','adapted','generated','line-local'),'unknown classification kind: '+str(kind))
        if kind in ('adapted','generated'):
            require(isinstance(rule.get('reviewed_blobs'),dict),'missing reviewed blob identities: '+path)
            require(all(v is None or re.fullmatch('[0-9a-f]{40}',v) for v in rule['reviewed_blobs'].values()),'invalid reviewed blob identity')
        if kind=='adapted':require(rule.get('surface') and rule.get('tests'),'adapted path needs stable surface and semantic tests: '+path)
        if kind=='generated':require(rule.get('provenance'),'generated path needs provenance: '+path)
    require(all(kind=='line-local' for kind in manifest.get('nonproduction_patterns',{}).values()),'wildcards cannot bless shared source')
    return manifest

def inventory(root,refs,manifest):
    validate_manifest(manifest);result={}
    for line,ref in refs.items():
        full=sha(root,ref)
        rows=git(root,'ls-tree','-r',full).splitlines()
        blobs={row.split('\t',1)[1]:row.split()[2] for row in rows}
        result[line]={'commit':full,'files':{p:classify(manifest,p) for p in blobs},'blobs':blobs}
    return result

def check(root,refs,manifest):
    inv=inventory(root,refs,manifest);issues=[]
    paths=sorted(set().union(*(set(row['files']) for row in inv.values())))
    for path in paths:
        rule=classify(manifest,path);kind=rule['kind']
        if kind=='unclassified':issues.append({'path':path,'result':'unclassified'});continue
        blobs={line:row['blobs'].get(path) for line,row in inv.items()}
        if kind=='identical' and len(set(blobs.values()))>1: issues.append({'path':path,'result':'shared-divergence','blobs':blobs})
        elif kind in ('adapted','generated'):
            # An axis token cannot bless arbitrary residual code. Exact reviewed blobs
            # bind all shared logic; any changed byte requires semantic review.
            for line,blob in blobs.items():
                if blob != rule.get('reviewed_blobs',{}).get(line):
                    issues.append({'path':path,'line':line,'result':('provenance-review-required' if kind=='generated' else 'adaptation-review-required'),'surface':rule.get('surface'),'blob':blob,'tests':rule.get('tests',[])})
    return {'sources':{k:v['commit'] for k,v in inv.items()},'issues':issues,'passed':not issues}

def patch_id(root,commit,mainline=None):
    args=['git','-C',str(root),'diff',commit+'^'+str(mainline or 1),commit]
    diff=subprocess.run(args,capture_output=True,check=True).stdout
    return subprocess.run(['git','patch-id','--stable'],input=diff,capture_output=True,check=True).stdout.decode().split()[0] if diff else None

def plan(root,batch,manifest):
    versioned(batch); require(batch.get('targets'),'explicit target refs required')
    require(all(re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)*', line) for line in batch['targets']), 'invalid stable target ID')
    commits=batch['commits']; seen=set(); ordered=[]
    for entry in commits:
        commit=sha(root,entry['commit']);require(commit==entry['commit'],'batch requires full commit IDs')
        require(commit not in seen,'duplicate commit');
        require(set(entry.get('prerequisites',[]))<=seen,'prerequisites must precede dependent commit')
        parents=git(root,'rev-list','--parents','-n','1',commit).split()[1:]
        require(parents,'root commits are not port batches')
        if len(parents)>1: require(entry.get('mainline') in range(1,len(parents)+1),'merge requires reviewed mainline parent')
        parent=parents[entry.get('mainline',1)-1]
        paths=git(root,'diff','--name-only',parent,commit).splitlines()
        ordered.append({'commit':commit,'mainline':entry.get('mainline'),'patch_id':patch_id(root,commit,entry.get('mainline')),'files':{p:classify(manifest,p) for p in paths},'prerequisites':entry.get('prerequisites',[])})
        seen.add(commit)
    targets={line:sha(root,ref) for line,ref in batch['targets'].items()}
    return {'schema_version':1,'targets':targets,'commits':ordered,'simulation':simulate(root,targets,ordered)}

def exact_patch_present(root,target,entry):
    if subprocess.run(['git','-C',str(root),'merge-base','--is-ancestor',entry['commit'],target],capture_output=True).returncode==0: return True
    if entry['mainline'] is not None: return False
    # git cherry uses patch identity, not subject/path guesses. Limit the input
    # range to this single ordinary commit; target history remains authoritative.
    result=git(root,'cherry',target,entry['commit'],entry['commit']+'^').splitlines()
    return any(row=='- '+entry['commit'] for row in result)

def simulate(root,targets,ordered):
    results={}
    # A shared bare clone reads source objects through alternates, while all
    # private indexes, conflict blobs and any Git helper work stay in scratch.
    # This also protects dirty/deleted caller files from implementation details
    # of future Git versions; planning never runs an apply in the caller tree.
    with tempfile.TemporaryDirectory(prefix='lss-port-simulation-') as tmp:
        repository=Path(tmp)/'repository.git';worktree=Path(tmp)/'worktree';worktree.mkdir()
        subprocess.run(['git','clone','--shared','--bare','--no-hardlinks',str(Path(root).resolve()),str(repository)],capture_output=True,check=True)
        for line,target in targets.items():
            steps=[];env=os.environ.copy();env['GIT_INDEX_FILE']=str(Path(tmp)/('index-'+line));env['GIT_WORK_TREE']=str(worktree)
            subprocess.run(['git','-C',str(repository),'read-tree',target],env=env,check=True,capture_output=True)
            for entry in ordered:
                commit=entry['commit']
                if exact_patch_present(repository,target,entry):
                    steps.append({'commit':commit,'result':'exact-patch-present'});continue
                patch=subprocess.run(['git','-C',str(repository),'diff','--binary',commit+'^'+str(entry['mainline'] or 1),commit],check=True,capture_output=True).stdout
                result=subprocess.run(['git','-C',str(repository),'apply','--cached','--3way','--allow-empty'],input=patch,env=env,capture_output=True)
                if result.returncode:
                    conflicts=subprocess.run(['git','-C',str(repository),'diff','--cached','--name-only','--diff-filter=U'],env=env,check=True,capture_output=True,text=True).stdout.splitlines()
                    steps.append({'commit':commit,'result':'conflict-or-adaptation-required','conflicts':conflicts,'diagnostic':result.stderr.decode(errors='replace')[:4096]});break
                steps.append({'commit':commit,'result':'private-index-applies'})
            results[line]=steps
    return results

def prepare(root,batch,manifest,target,destination,branch):
    proposal=plan(root,batch,manifest);require(target in proposal['targets'],'unknown target')
    destination=Path(destination).absolute();require(not destination.exists(),'destination already exists; will not overwrite')
    require(not git(root,'status','--porcelain').strip(),'source worktree is dirty; commit/review inputs before preparing')
    require(branch.startswith('port/'),'isolated branch must start with port/')
    ref=proposal['targets'][target]
    git(root,'worktree','add','-b',branch,str(destination),ref)
    state={'schema_version':1,'proposal':proposal,'target':target,'branch':branch,'completed':[],'state':'in-progress'}
    statepath=Path(git(destination,'rev-parse','--absolute-git-dir').strip())/'lss-port-state.json'
    def save():statepath.write_text(json.dumps(state,indent=2)+'\n')
    save()
    for entry in proposal['commits']:
        commit=entry['commit']
        # Exact patch identity only; adapted equivalence is never inferred.
        already=exact_patch_present(destination,'HEAD',entry)
        if already: state['completed'].append({'commit':commit,'result':'exact-patch-present'});save();continue
        args=['cherry-pick']
        if entry['mainline']:args+=['-m',str(entry['mainline'])]
        p=subprocess.run(['git','-C',str(destination),*args,commit],capture_output=True,text=True)
        if p.returncode:
            state.update(state='conflict',failed_commit=commit,head=sha(destination,'HEAD'),conflicts=git(destination,'diff','--name-only','--diff-filter=U').splitlines())
            save();raise Invalid(f'port stopped; conflict and input identities preserved in {statepath}')
        state['completed'].append({'commit':commit,'result':'picked','target_commit':sha(destination,'HEAD')});save()
    state['state']='prepared-review-required';state['head']=sha(destination,'HEAD');save();return state

def resume(destination):
    destination=Path(destination)
    statepath=Path(git(destination,'rev-parse','--absolute-git-dir').strip())/'lss-port-state.json'
    state=versioned(load(statepath))
    require(state['state']=='conflict','only a preserved conflict can be resumed')
    require(git(destination,'symbolic-ref','--short','HEAD').strip()==state['branch'],'port branch identity changed')
    require(sha(destination,'HEAD')==state['head'],'HEAD changed since saved conflict; review before resuming')
    require(not git(destination,'diff','--name-only','--diff-filter=U').strip(),'unresolved conflict files remain')
    require(git(destination,'rev-parse','CHERRY_PICK_HEAD').strip()==state['failed_commit'],'cherry-pick identity changed')
    for entry in state['proposal']['commits']:
        require(sha(destination,entry['commit'])==entry['commit'],'missing original source object')
        require(patch_id(destination,entry['commit'],entry['mainline'])==entry['patch_id'],'original input patch changed')
    git(destination,'-c','core.editor=true','cherry-pick','--continue')
    failed=state['failed_commit'];state['completed'].append({'commit':failed,'result':'resolved-review-required','target_commit':sha(destination,'HEAD')})
    state['state']='in-progress'
    pending=False
    for entry in state['proposal']['commits']:
        if entry['commit']==failed:pending=True;continue
        if not pending:continue
        # Match prepare: exact identity only, never inferred adapted equivalence.
        if exact_patch_present(destination,'HEAD',entry):
            state['completed'].append({'commit':entry['commit'],'result':'exact-patch-present'})
            statepath.write_text(json.dumps(state,indent=2)+'\n')
            continue
        args=['cherry-pick']+(['-m',str(entry['mainline'])] if entry['mainline'] else [])+[entry['commit']]
        p=subprocess.run(['git','-C',str(destination),*args],capture_output=True,text=True)
        if p.returncode:
            state.update(state='conflict',failed_commit=entry['commit'],head=sha(destination,'HEAD'),conflicts=git(destination,'diff','--name-only','--diff-filter=U').splitlines())
            statepath.write_text(json.dumps(state,indent=2)+'\n')
            raise Invalid('next conflict preserved: '+str(statepath))
        state['completed'].append({'commit':entry['commit'],'result':'picked','target_commit':sha(destination,'HEAD')})
        statepath.write_text(json.dumps(state,indent=2)+'\n')
    state.update(state='prepared-review-required',head=sha(destination,'HEAD'))
    statepath.write_text(json.dumps(state,indent=2)+'\n');return state

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=ROOT)
    sub=p.add_subparsers(dest='command',required=True)
    for command in ('inventory','check'):
        s=sub.add_parser(command);s.add_argument('--refs',type=Path);s.add_argument('--output',type=Path)
    for command in ('plan','prepare'):
        s=sub.add_parser(command);s.add_argument('batch',type=Path)
        if command=='prepare':
            s.add_argument('--target',required=True);s.add_argument('--destination',required=True,type=Path);s.add_argument('--branch',required=True)
    s=sub.add_parser('resume');s.add_argument('destination',type=Path)
    a=p.parse_args()
    try:
        manifest=versioned(load(a.root/'config/lines/classification.json'))
        if a.command=='resume':result=resume(a.destination)
        elif a.command in ('inventory','check'):
            refs=load(a.refs) if a.refs else {k:v['commit'] for k,v in load(a.root/'config/compatibility/source-refs.json')['sources'].items()}
            result=inventory(a.root,refs,manifest) if a.command=='inventory' else check(a.root,refs,manifest)
        else:
            batch=load(a.batch);result=plan(a.root,batch,manifest) if a.command=='plan' else prepare(a.root,batch,manifest,a.target,a.destination,a.branch)
        output=json.dumps(result,indent=2,sort_keys=True)+'\n'
        if getattr(a,'output',None):a.output.write_text(output)
        else: print(output,end='')
        if a.command=='check' and not result['passed']: return 1
    except (Invalid,OSError,ValueError,KeyError,subprocess.CalledProcessError) as e:p.exit(1,f'lines: {e}\n')
    return 0
if __name__=='__main__':sys.exit(main())
