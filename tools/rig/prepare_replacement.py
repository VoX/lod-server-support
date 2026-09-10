"""Derive two explicitly owned servers for same-dimension Xaero replacement testing."""
import copy,json,sys
from pathlib import Path
from bind_endpoints import bindings

def prepare(runtime,replacement_endpoint,parse_endpoint):
    result=copy.deepcopy(runtime)
    if result.get('additional_bind_endpoints'):raise ValueError('replacement recipe already has extra bindings')
    result['additional_bind_endpoints']=[replacement_endpoint]
    bindings(result,parse_endpoint)
    first=next((row for row in result['launches'] if row['id']=='server'),None)
    if first is None or first['cwd']!='server':raise ValueError('standard owned Fabric server launch required')
    if result.get('server_profile',{}).get('id')!='mc1211-fabric-server':raise ValueError('exact 1.21.1 Fabric server profile required')
    stages=result['stage_files']
    if not any(row['target'].startswith('server/mods/lss-wi6-') for row in stages):raise ValueError('maintained transport-close fixture required')
    if not any('/mods/lss-wi5-' in row['target'] and not row['target'].startswith('server/') for row in stages):raise ValueError('maintained reception fixture required')
    if result.get('world_digest') or any(row['target'].endswith(('level.dat','.mca','.sqlite')) for row in stages):
        raise ValueError('replacement recipe requires two fresh disposable worlds')
    if any(row['target'].startswith('server-replacement/') for row in stages):
        raise ValueError('replacement server target already exists')
    original=list(stages)
    stages.extend(dict(row,target='server-replacement/'+row['target'][len('server/'):]) for row in original if row['target'].startswith('server/'))
    for name,value in list(result['generated_files'].items()):
        if name.startswith('server/'):
            result['generated_files']['server-replacement/'+name[len('server/'):]]=value
    for name,value in list(result.get('immutable_trees',{}).items()):
        if name.startswith('server/'):
            result['immutable_trees']['server-replacement/'+name[len('server/'):]]=copy.deepcopy(value)
    host,port=parse_endpoint(replacement_endpoint)
    props=result['generated_files']['server-replacement/server.properties']
    rows=[line for line in props.splitlines() if not line.startswith(('server-ip=','server-port=','level-seed='))]
    result['generated_files']['server-replacement/server.properties']='\n'.join(rows+[f'server-ip={host}',f'server-port={port}','level-seed=project-improvements-replacement'])+'\n'
    second=copy.deepcopy(first);second['id']='server-replacement';second['cwd']='server-replacement'
    if any(arg.startswith(('-Dlss.rig.serverRoot=','-Dlss.rig.abruptClose=')) for arg in first['argv']):raise ValueError('transport-close flags already set')
    first['argv'][1:1]=['-Dlss.rig.abruptClose=true','-Dlss.rig.serverRoot={run}/server']
    # Start both servers before the observer. Neither is an existing personal server.
    result['launches'].insert(result['launches'].index(first)+1,second)
    for value in (result['client_endpoint'],replacement_endpoint):
        if parse_endpoint(value)[0] not in ('::1','127.0.0.1'):raise ValueError('explicit supported loopback endpoint required')
    config='instances/lss-rig-client/instance.cfg'
    original=result['generated_files'].get(config,'')
    marker='JvmArgs=-Dlss.rig.runId={run_id}'
    if original.count(marker)!=1 or 'lss.rig.initialEndpoint' in original or 'lss.rig.replacementEndpoint' in original:
        raise ValueError('standard isolated Prism JVM arguments required')
    result['generated_files'][config]=original.replace(marker,marker+' -Dlss.rig.initialEndpoint='+result['client_endpoint']+' -Dlss.rig.replacementEndpoint='+replacement_endpoint)
    if any(row['id']=='lifecycle-controller' for row in result['launches']):raise ValueError('lifecycle controller already exists')
    result['launches'].append({'id':'lifecycle-controller','cwd':'client','argv':[sys.executable,str(Path(__file__).with_name('drive_receive_lifecycle.py').resolve()),'{run}']})
    return result
