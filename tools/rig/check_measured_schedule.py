"""Check preregistered offered load, independently of delivered/eligible outcomes."""

def check(oracle,events,origin):
    errors=[];seen=set()
    subjects={f'RigSubject{letter}':i for i,letter in enumerate('ABCD')}
    rows=[row for row in oracle if 'target_sequence' in row and row.get('event')=='target']
    for row in rows:
        subject=row.get('subject');sequence=row.get('target_sequence');key=(subject,sequence)
        if subject not in subjects or type(sequence) is not int or not 1<=sequence<=5760:
            errors.append('invalid measured subject/sequence');continue
        if key in seen:errors.append('duplicate measured offer')
        seen.add(key);round=sequence-1;cell=round%256
        expected=(subjects[subject]*256+12+cell//16,-7+cell%16)
        if (row.get('chunk_x'),row.get('chunk_z'))!=expected:errors.append('measured offer differs from seeded domain')
        block='diamond_block' if (round//256)%2==0 else 'gold_block'
        if (type(row.get('expected_source')) is not int or row.get('expected_source')!=0 or row.get('allowed_sources')!=[0,1,3]
                or any(type(source) is not int for source in row.get('allowed_sources',[]))
                or row.get('block_y')!=64 or row.get('expected_block')!=block or row.get('requires_ack') is not True):
            errors.append('measured target specification changed')
        stamp=row.get('offered_ns');due=origin+round*125_000_000
        if type(stamp) is not int or not due<=stamp<=due+1_000_000_000:errors.append('measured offer outside fixed cadence')
    if len(rows)!=23040 or len(seen)!=23040:errors.append('all 23040 preregistered offers required')
    if any(row.get('event','').startswith('offer_schedule_') for row in events):errors.append('producer reported incomplete or delayed schedule')
    closes=[row.get('time_ns') for row in events if row.get('event')=='offers_closed']
    if len(closes)!=1 or type(closes[0]) is not int or not origin+720_000_000_000<=closes[0]<=origin+721_000_000_000:
        errors.append('measured offer-close time invalid')
    return {'status':'failed' if errors else 'passed','errors':sorted(set(errors)),'offered_targets':len(rows)}
