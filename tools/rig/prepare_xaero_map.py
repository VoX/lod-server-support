"""Compose four exact primary native map lanes from an owned Prism base recipe."""
import copy,sys
from pathlib import Path
from rig import sha,regular
PROFILES={'mc1211-fabric-modern','mc1211-fabric-legacy','mc1211-neo-modern','mc1211-neo-legacy-xaero'}

def build(runtime,profile,map_fixture,connect_fixture):
 if profile.get('id') not in PROFILES or profile.get('line')!='1.21.1' or profile.get('route')!=('connector' if profile.get('id')=='mc1211-neo-modern' else 'native'):raise ValueError('exact primary native map profile required')
 if runtime.get('backend')!='isolated-linux-prism' or {x['id']for x in runtime.get('launches',[])}!={'server','client'}:raise ValueError('owned native client/server base required')
 expected='ea84c45cb1b994a7738515670b281bcd373a9d9cc3d8d3a24b84a4baf302b9d6' if profile['platform']=='fabric' else 'd60899112e84616ba52a993c1722fd891872acc4779bfac34d9b170001451c6f'
 if not any(row.get('sha256')==expected and row.get('enabled',True) for row in profile.get('artifacts',[])):raise ValueError('exact inspected native Xaero1.45.0 required')
 result=copy.deepcopy(runtime)
 for path,name in [(map_fixture,'lss-xaero-map-fixture.jar'),(connect_fixture,'lss-map-connect-fixture.jar')]:
  path=regular(Path(path));result['stage_files'].append(dict(source=str(path.resolve()),target='instances/lss-rig-client/minecraft/mods/'+name,sha256=sha(path)))
 config='instances/lss-rig-client/instance.cfg';marker='JvmArgs=-Dlss.rig.runId={run_id}'
 if result['generated_files'][config].count(marker)!=1:raise ValueError('known private JVM argument declaration required')
 result['generated_files'][config]=result['generated_files'][config].replace(marker,marker+' -Dlss.rig.initialEndpoint='+result['client_endpoint']+' -Dlss.xaeromap.pauseMaxMillis=15000 -Dlss.xaeromap.enabled=true -Dlss.xaeromap.evidence={run}/evidence/xaero-map.jsonl -Dlss.xaeromap.arm={run}/evidence/xaero-map-arm-save')
 import json
 client='instances/lss-rig-client/minecraft/config/lss-client-config.json';values=json.loads(result['generated_files'].get(client,'{}'));values.update(receiveServerLods=True,enableXaeroMapBridge=True,enableJoinSlowStart=False);result['generated_files'][client]=json.dumps(values)+'\n'
 server='server/config/lss-server-config.json';values=json.loads(result['generated_files'].get(server,'{}'));values.update(lodDistanceChunks=32,enableChunkGeneration=False);result['generated_files'][server]=json.dumps(values)+'\n'
 # Preserve native Options data-version and defaults; never add unversioned key overrides.
 options='instances/lss-rig-client/minecraft/options.txt';lines=result['generated_files'].get(options,'').splitlines();lines=[line for line in lines if not line.startswith(('renderDistance:','simulationDistance:','tutorialStep:'))];lines+=['renderDistance:4','simulationDistance:5','tutorialStep:none'];result['generated_files'][options]='\n'.join(lines)+'\n'
 result['generated_files']['server/server.properties']+='difficulty=peaceful\nview-distance=4\nsimulation-distance=4\n'
 result['launches'].append(dict(id='map-controller',cwd='client',argv=[sys.executable,str(Path(__file__).with_name('drive_xaero_map.py').resolve()),'{run}']))
 result['map_fixture_scope']='Actual network/bridge/native Xaero observations; existing WI5 fixture supplies native loopback connect only, no WI5 arm files or lifecycle assertions.'
 return result

if __name__=='__main__':
 import argparse,json
 from rig import read,write,require_lock
 p=argparse.ArgumentParser()
 for name in ('runtime','profile','map-fixture','connect-fixture','output'):p.add_argument('--'+name,required=True,type=Path)
 a=p.parse_args();require_lock();a.output.mkdir(parents=True,exist_ok=False)
 profile=read(a.profile);runtime=build(read(a.runtime),profile,a.map_fixture,a.connect_fixture)
 scenario=read(Path(__file__).with_name('scenarios')/'xaero-map.json')
 for name,value in [('profile',profile),('runtime',runtime),('scenario',scenario)]:write(a.output/(name+'.json'),value)
 print(json.dumps({'recipe':str(a.output),'profile':profile['id'],'map_fixture_sha256':sha(a.map_fixture),'connect_fixture_sha256':sha(a.connect_fixture)}))
