"""Require a real same-frame seated dispatcher failure and restored subsequent draw."""
import re

ASSERTIONS = ('seated_proxy_draw', 'fault_injected', 'next_proxy_draw',
              'matrices_restored', 'name_tag_draw')

def check(text):
    rows=[]
    errors=[]
    for line in text.splitlines():
        match=re.search(r'\[WI9-FIXTURE\] ([A-Z_]+) pass=(\d+) (.*)',line)
        if not match:
            continue
        event,frame,detail=match.groups()
        fields=dict(word.split('=',1) for word in detail.split() if '=' in word)
        rows.append((event,int(frame),fields))
        if 'FAIL' in event or 'TIMEOUT' in event:
            errors.append('native fixture failure: '+event)
    required=('ARMED','INJECTED','NEXT_PROXY_HEAD','NEXT_PROXY_RETURN','TAG_HEAD','PASS_SAME_FRAME')
    selected={}
    for event in required:
        matches=[(i,frame,fields) for i,(name,frame,fields) in enumerate(rows) if name==event]
        # There may be multiple native tags, but one actual fault and subsequent proxy.
        if not matches or (event!='TAG_HEAD' and len(matches)!=1):
            errors.append('missing/duplicate '+event)
        elif event=='TAG_HEAD':
            selected[event]=matches[0]
            if any(fields.get('sentinel_and_matrices_restored')!='true' for _,_,fields in matches):
                errors.append('a tag observed unrestored matrices')
        else:
            selected[event]=matches[0]
    if len(selected)==len(required):
        ordered=[selected[event][0] for event in required]
        if ordered!=sorted(ordered):errors.append('native draw events out of order')
        frame=selected['INJECTED'][1]
        if selected['ARMED'][1]>=frame:errors.append('fault lacked an earlier real draw premise')
        if any(selected[event][1]!=frame for event in required[2:]):errors.append('recovery was not in the fault frame')
        expectations={
            'ARMED':{'observed_two_real_proxy_draws':'true','native_players_absent':'true'},
            'INJECTED':{'passenger':'true','real_dispatcher_push_translate':'true'},
            'NEXT_PROXY_HEAD':{'sentinel_and_matrices_restored':'true'},
            'PASS_SAME_FRAME':{'outer_unwind':'true','crash_latched':'false','assertion_failed':'false'}}
        for event,values in expectations.items():
            if any(selected[event][2].get(key)!=value for key,value in values.items()):
                errors.append(event+' native assertion failed')
        final=selected['PASS_SAME_FRAME'][2]
        for key in ('next_starts','next_returns','tag_starts','tag_returns'):
            if not final.get(key,'').isdigit() or int(final[key])<1:errors.append('missing completed native draw: '+key)
        first=selected['INJECTED'][2].get('uuid')
        second=selected['NEXT_PROXY_HEAD'][2].get('uuid')
        if not first or not second or first==second or selected['NEXT_PROXY_RETURN'][2].get('uuid')!=second:
            errors.append('independent subsequent proxy identity missing')
        if selected['ARMED'][2].get('first_seated')!=first or selected['ARMED'][2].get('second')!=second:
            errors.append('draw identities differ from armed premise')
    healthy=[(i,frame,fields) for i,(name,frame,fields) in enumerate(rows) if name=='HEALTHY_READY']
    if healthy:
        if len(healthy)!=1:errors.append('duplicate healthy native draw premise')
        i,frame,fields=healthy[0]
        if any(fields.get(key)!='true' for key in ('both_seated_returns','native_players_absent','beyond_128','scoping')):
            errors.append('healthy native capture premise failed')
        armed=selected.get('ARMED')
        if armed and (i>=armed[0] or frame>armed[1]
                or any(fields.get(key)!=armed[2].get(key) for key in ('first_seated','second'))):
            errors.append('healthy native capture did not precede matching armed subjects')
    return {'passed':not errors,'errors':errors,'assertions':{key:not errors for key in ASSERTIONS}}
