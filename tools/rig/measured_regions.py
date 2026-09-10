"""Keep only whole, independently qualified Folia ticks in measured source sessions."""
from performance import overlap

def bind(result,source_events,start,end):
    if result.get('status')!='passed':raise ValueError('raw owning-region proof failed')
    registrations={};ends={}
    for row in source_events:
        connection=row.get('connection_id')
        if row.get('event')=='product_registration_observed':registrations[connection]=(row.get('subject'),row['time_ns'])
        if row.get('event') in ('quit','session_end'):ends[connection]=(row.get('subject'),row['time_ns'])
    qualified=[]
    for sample in result.get('region_samples',[]):
        connection=sample.get('connection_id');begin=registrations.get(connection);finish=ends.get(connection)
        if not begin or not finish or begin[0]!=sample.get('subject') or finish[0]!=sample.get('subject'):continue
        if max(start,begin[1])<=sample['start_ns']<sample['end_ns']<=min(end,finish[1]):qualified.append(sample)
    if not overlap(qualified):raise ValueError('no whole owning-region overlap within measured current source sessions')
    return dict(result,region_samples=qualified,qualified_tick_samples=len(qualified),scope='measured-current-source-session-owning-ticks')
