#!/usr/bin/env python3
"""Fetch exact snapshot objects, validate local candidate, emit an ephemeral report."""
import argparse,json,subprocess,sys
from pathlib import Path
from catalog import ROOT, Invalid, git, load, require, validate, render
sys.path.insert(0,str(ROOT/'tools/lines'))
from lines import check

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fetch',action='store_true');p.add_argument('--report',type=Path,default=Path('compatibility-candidate-report.json'));a=p.parse_args()
 try:
  sources=load(ROOT/'config/compatibility/source-refs.json')['sources']
  for source in sources.values():
   ref=source['commit']
   exists=subprocess.run(['git','-C',str(ROOT),'cat-file','-e',ref+'^{commit}'],stderr=subprocess.DEVNULL).returncode==0
   if not exists:
    require(a.fetch,'missing exact source object '+ref+'; fetch explicitly')
    subprocess.run(['git','-C',str(ROOT),'fetch','--no-tags','origin',ref],check=True)
  validate(ROOT);render(ROOT,True)
  refs={line:value['commit'] for line,value in sources.items()}
  candidate=git(ROOT,'rev-parse','HEAD').strip();line=load(ROOT/'config/compatibility/line.json')['facts']['line'];refs[line]=candidate
  report=check(ROOT,refs,load(ROOT/'config/lines/classification.json'))
  report['candidate_line']=line;report['comparison_baseline']=sources
  # A reviewed batch names exact allowed candidate blobs. It never suppresses a
  # path wholesale, and cannot bless a newly changed blob after review.
  batchfile=ROOT/'config/lines/port-batch.json'
  if batchfile.exists():
   from port_batch import validate_batch, apply_batch
   batch=validate_batch(load(batchfile),{k:v['commit'] for k,v in sources.items()})
   rows=git(ROOT,'ls-tree','-r',candidate).splitlines()
   candidate_blobs={row.split('\t',1)[1]:row.split()[2] for row in rows}
   apply_batch(report,batch,line,candidate_blobs)
  a.report.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
  require(report['passed'],'candidate has unexplained cross-line drift; inspect '+str(a.report))
 except (Invalid,OSError,KeyError,subprocess.CalledProcessError) as e:p.exit(1,f'compat CI: {e}\n')
if __name__=='__main__':main()
