"""Deterministic owned-console preparation only; timestamps come from real receipts."""
import json

def height(x):return 64+((x-432)%8)
def build(subject):
 import re
 if not re.fullmatch('[A-Za-z0-9_]+',subject):raise ValueError('simple exact offline identity required')
 commands=['gamerule randomTickSpeed 0','gamerule doDaylightCycle false','time set noon','weather clear','gamemode spectator '+subject,'tp '+subject+' 264 90 264','forceload add 432 240 591 335']
 for x in range(432,592):
  commands += [f'fill {x} 64 240 {x} 96 335 air',f'fill {x} 64 240 {x} {height(x)} 335 gold_block']
 commands.append('save-all flush')
 targets=[]
 for chunk in [31,32]:
  values=[height(chunk*16+x)for x in range(16)for z in range(16)]
  targets.append({'chunk':[chunk,16],'initial_floor_y':values,'final_floor_y':values,'initial_block':'gold_block','final_block':'diamond_block'})
 edits=[]
 for x in range(496,528):edits.append(f'fill {x} 64 256 {x} {height(x)} 271 diamond_block')
 edits.append('save-all flush')
 return {'prepare_commands':commands,'targets':targets,'native_visit_command':f'tp {subject} 504 90 264','return_command':f'tp {subject} 264 90 264','paused_edit_commands':edits,'timestamp_policy':'seed_ready_ns/native_visit_started_ns/edit_ready_ns must come from actual owned command receipts; this plan creates no observed values or proof.'}
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('--subject',required=True);a=p.parse_args();print(json.dumps(build(a.subject),indent=2))
