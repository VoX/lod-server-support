"""Observed native registrations must belong to real bounded connection intervals."""
def check(events,accepted):
    errors=[];joins={};endings={};registered=set()
    for row in events:
        event=row.get('event');connection=row.get('connection_id')
        if event=='join':
            if connection in joins:errors.append('duplicate native connection join')
            joins[connection]=row
        elif event in ('quit','session_end'):
            if connection in endings:errors.append('duplicate native connection end')
            endings[connection]=row
    for row in events:
        if row.get('event')!='product_registration_observed':continue
        connection=row.get('connection_id');joined=joins.get(connection);end=endings.get(connection)
        if connection in registered:errors.append('duplicate native current-session registration')
        registered.add(connection)
        at=row.get('time_ns')
        if not joined or not end or row.get('subject')!=joined.get('subject') or end.get('subject')!=joined.get('subject') or any(type(value) is not int for value in (at,joined.get('time_ns'),end.get('time_ns'))) or not joined['time_ns']<=at<=end['time_ns']:
            errors.append('native product registration outside current joined session')
    required={'RigSubject'+letter for letter in 'ABCD'}
    admitted={(row.get('connection_id'),row.get('subject')) for row in accepted if row.get('accepted') is True}
    if not joins or registered!=set(joins) or {r.get('subject') for r in joins.values()}!=required or any((connection,row.get('subject')) not in admitted for connection,row in joins.items()):
        errors.append('actual four-subject native product sessions required')
    return errors
