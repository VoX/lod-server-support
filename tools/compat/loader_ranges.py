"""Run actual loader range semantics using separately pinned tool dependencies."""
import base64,hashlib,os,subprocess
from pathlib import Path
from catalog import require

def evaluate(queries,runtime):
    require(runtime.get('schema_version')==1,'unsupported range runtime schema')
    jars=[]
    for artifact in runtime['classpath']:
        path=Path(artifact['path']);require(path.is_file() and not path.is_symlink(),'range dependency must be regular cached jar')
        with path.open('rb') as stream:require(hashlib.file_digest(stream,'sha256').hexdigest()==artifact['sha256'],'range runtime hash mismatch')
        jars.append(str(path))
    require(jars,'range runtime empty')
    if not queries:return []
    lines=[]
    for version,constraint,dialect in queries:
        require(isinstance(constraint,str),'range bridge expects expanded string alternatives')
        encoded=[base64.b64encode(v.encode()).decode() for v in (version,constraint)]
        lines.append(dialect+'\t'+'\t'.join(encoded))
    result=subprocess.run([runtime['java'],'--class-path',os.pathsep.join(jars),str(Path(__file__).with_name('LoaderRanges.java'))],input='\n'.join(lines)+'\n',capture_output=True,text=True,timeout=60)
    require(result.returncode==0,'actual loader range runtime failed')
    output=result.stdout.splitlines();require(len(output)==len(queries),'range runtime returned incomplete results')
    require(all(value in ('accepted','rejected','unsupported') for value in output),'unknown range runtime result')
    return output
