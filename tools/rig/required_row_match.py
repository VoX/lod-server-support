"""Required acceptance is scoped to one explicit artifact and scenario target."""
import re
FIELDS=('profile_hash','scenario_hash','scenario_checker_sha256','candidate_sha256')

def complete(t):
 if not isinstance(t,dict):return False
 if any(not isinstance(t.get(k),str) or not re.fullmatch('[0-9a-f]{64}',t[k]) for k in FIELDS):return False
 if type(t.get('scenario_version')) is not int or t['scenario_version']<1:return False
 if not isinstance(t.get('candidate_target'),str) or not t['candidate_target']:return False
 for key in ('fixture_artifacts','candidate_artifacts'):
  a=t.get(key)
  if not isinstance(a,dict) or not all(isinstance(k,str) and k and isinstance(v,str) and re.fullmatch('[0-9a-f]{64}',v) for k,v in a.items()):return False
 return bool(t['candidate_artifacts']) and t['candidate_artifacts'].get(t['candidate_target'])==t['candidate_sha256']

def same_target(row,record):
 t=row.get('acceptance_target')
 if not complete(t):return False
 if record.get('profile_id')!=row.get('profile_id') or record.get('scenario')!=row.get('scenario_id') or record.get('profile_hash')!=t['profile_hash']:return False
 a=record.get('run_manifest',{})
 if any(a.get(k)!=t[k] for k in (*FIELDS[1:],'scenario_version','candidate_target','fixture_artifacts','candidate_artifacts')):return False
 expected=row.get('required_participants',{})
 if expected and (not isinstance(a.get('participant_profiles'),dict) or any(a['participant_profiles'].get(role)!=value for role,value in expected.items())):return False
 if 'participant_profiles' in t and a.get('participant_profiles')!=t['participant_profiles']:return False
 if row.get('required_client_profile_hash') and record['profile_hash']!=row['required_client_profile_hash']:return False
 if row.get('scenario_id')=='server-smoke':
  w=row.get('required_server',{})
  if not w or a.get('execution_route')!='native-server-smoke' or a.get('scenario_version')!=row.get('scenario_version'):return False
  for k,f in [('id','server_profile_id'),('profile_hash','server_profile_hash'),('platform','server_platform'),('candidate_target','server_candidate_target')]:
   if not w.get(k) or a.get(f)!=w[k]:return False
  if a.get('candidate_target')!=w['candidate_target']:return False
 return True

def matches(row,record):return record.get('result')=='pass' and same_target(row,record)

def evaluate(row,records):
 if not complete(row.get('acceptance_target')):return dict(status='unverified',reason='missing complete explicit acceptance target',selected=[])
 selected=[r for r in records if same_target(row,r)]
 if not selected:return dict(status='unverified',reason='no exact-target evidence',selected=[])
 newest=max(r['timestamp'] for r in selected);selected=[r for r in selected if r['timestamp']==newest]
 passed=all(r.get('result')=='pass' for r in selected)
 return dict(status='passed' if passed else 'unverified',reason='latest exact-target attempt passed' if passed else 'latest exact-target attempt did not pass',selected=selected)
