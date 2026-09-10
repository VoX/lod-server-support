"""Validate native adapter-denial observations; never claims physical Netty saturation."""
import re

def check(text):
 rows=[];errors=[]
 for line in text.splitlines():
  match=re.search(r'\[WI6-FIXTURE\] (\w+) tick=(\d+) (.*)',line)
  if match:rows.append((match[1],int(match[2]),dict(re.findall(r'(\w+)=([^ ]+)',match[3])),match[3]))
 def one(name):
  values=[r for r in rows if r[0]==name]
  if len(values)!=1:errors.append('expected exactly one '+name);return None
  return values[0]
 def number(row,key,minimum=0):
  value=row[2].get(key,'')
  if not re.fullmatch(r'0|[1-9][0-9]{0,9}',value) or int(value)<minimum:
   errors.append('invalid integer '+row[0]+'.'+key);return -1
  return int(value)
 events={name:one(name) for name in ('READY','ARMED','OFF_ENTERED','ON_ENTERED','REPLACEMENT_ROSTER_ACCEPTED','REPLACEMENT_UPDATE_ACCEPTED','DEBT_RELEASED','PASS_SEND_ADMISSION')}
 if any(row[0]=='FAIL_OR_INCONCLUSIVE' for row in rows):errors.append('native fixture failure')
 if any(b[1]<a[1] for a,b in zip(rows,rows[1:])):errors.append('native log ticks regress')
 declined=[r for r in rows if r[0]=='CLEAR_DECLINED'];other=[r for r in rows if r[0]=='UNAFFECTED_CLEAR_ACCEPTED'];baseline=[r for r in rows if r[0]=='UNAFFECTED_BASELINE']
 if all(events.values()):
  ready,arm,off,on,roster,update,debt,end=events.values()
  event_positions=[rows.index(row) for row in events.values()]
  if event_positions!=sorted(event_positions):errors.append('native event log order invalid')
  if not (ready[1]<=arm[1]<=off[1]<on[1]<=roster[1]<=update[1]<=debt[1]<=end[1]):errors.append('native event ordering invalid')
  if on[1]-off[1]>1200 or update[1]-on[1]>200 or end[1]-update[1]<100:errors.append('native retry/recovery observation outside bounds')
  if arm[2].get('actual_roster_and_update_accepted')!='true':errors.append('missing actual accepted baseline')
  epoch=number(on,'held_epoch',1);base=number(arm,'baseline_epoch');index=number(arm,'subject_index')
  if number(off,'baseline_epoch')!=base or epoch!=base+1:errors.append('held epoch not exact baseline successor')
  br=[r for r in rows if r[0]=='BASELINE_ROSTER' and r[1]<=arm[1]]
  bu=[r for r in rows if r[0]=='BASELINE_UPDATE' and r[1]<=arm[1]]
  if not br or not bu or number(br[-1],'epoch')!=base or number(br[-1],'subject_index')!=index or number(bu[-1],'epoch')!=base or not(rows.index(br[-1])<rows.index(bu[-1])<rows.index(arm)):
   errors.append('actual accepted baseline roster/update identity absent')
  if any(number(r,'epoch')!=epoch for r in (roster,update)):errors.append('replacement epoch differs from held clear')
  number(roster,'subject_index')
  for key in ('clearPending','fullRosterPending','controlFullPending'):
   if debt[2].get(key)!='false':errors.append('native debt remains or unobserved: '+key)
  if debt[2].get('owner_thread')!='true':errors.append('debt observation lacks owner context')
  if end[2].get('obsolete_clear_attempts')!='0' or end[2].get('fixture_scope')!='adapter_denial_not_physical_netty':errors.append('invalid obsolete-clear/scope result')
  if any(end[2].get(key)!='true' for key in ('replacement_roster','replacement_subject_update')):errors.append('final replacement not accepted')
  if number(end,'observation_ticks',100)!=end[1]-update[1]:errors.append('final observation interval inconsistent')
  count=number(on,'held_retries',3)
  if number(end,'held_retries',3)!=count or number(end,'held_epoch',1)!=epoch:errors.append('final retry identity inconsistent')
  unaffected=ready[2].get('unaffected_uuid')
  if not unaffected or not baseline or any(r[2].get('viewer')!=unaffected or r[2].get('actual_adapter_accepted')!='true' or r[1]>=off[1] for r in baseline):errors.append('unaffected accepted baseline identity missing')
  if not other or any(r[2].get('viewer')!=unaffected or not(off[1]<=r[1]<on[1]) or r[2].get('actual_adapter_accepted')!='true' for r in other):errors.append('unaffected viewer made no actual progress during denial')
  counts=[number(r,'count',1) for r in declined]
  if len(counts)<3 or counts[:3]!=[1,2,3] or any(b<=a for a,b in zip(counts,counts[1:])) or (counts and counts[-1]>count):errors.append('missing/regressing actual denial retries')
  if count>on[1]-off[1]:errors.append('retry count exceeds native owner ticks')
  if any(not(off[1]<=r[1]<on[1]) or number(r,'epoch',1)!=epoch or r[2].get('actual_adapter_return')!='false' for r in declined):errors.append('denied retry identity/window mismatch')
 passed=not errors
 return dict(passed=passed,errors=errors,assertions={key:passed for key in ('adapter_denial_exercised','unaffected_subject_progress','bounded_retry','debt_released')},observations=len(rows))
