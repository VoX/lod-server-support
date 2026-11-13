#!/usr/bin/env python3
"""Evaluate required rows against this line's validated records."""
import argparse,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from required_row_match import evaluate
from catalog import validate

def status(root):
 root=Path(root);rows_,_,latest_records=validate(root)
 line=rows_[0][1]['facts']['line']
 matrix=json.loads((root/'tools/rig/required-matrix.json').read_text());rows=[]
 for row in matrix['rows']:
  result=evaluate(row,latest_records);selected={(r['run_id'],r['run_hash']) for r in result['selected']}
  evidence=[{'line':line,'run_id':record['run_id'],'run_hash':record['run_hash'],'result':record['result']} for record in latest_records if (record['run_id'],record['run_hash']) in selected]
  rows.append(dict(id=row['id'],status=result['status'],reason=result['reason'],evidence=evidence))
 return {'schema_version':1,'evidence_scope':"this line's validated records",'rows':rows}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();print(json.dumps(status(a.root),indent=2))
