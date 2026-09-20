"""Bounded stdin commands to an already owned launch; never shell commands."""
import json
import os
from pathlib import Path
import time
from rig import alive, identity, regular, write

class Commands:
    def __init__(self,root,launches):
        self.root=root
        self.directory=root/'commands';self.directory.mkdir(exist_ok=True)
        self.results=self.directory/'results';self.results.mkdir(exist_ok=True)
        self.targets={launch['id']:(proc,identity(proc.pid)) for launch,proc in launches}
        self.pending={}
    def poll(self):
        now=time.time()
        files=sorted(self.directory.glob('*.json'))
        if len(files)>128:raise ValueError('owned command queue exceeds 128 requests')
        for path in files:
            if path.name in self.pending:continue
            if not path.stem.replace('-','').replace('_','').isalnum():raise ValueError('invalid command request ID')
            result_path=self.results/path.name
            if result_path.exists():raise ValueError('command request ID replay')
            regular(path)
            if path.stat().st_size>8192:raise ValueError('command request exceeds 8KiB')
            request=json.loads(path.read_text())
            target=request['launch_id'];command=request['command']
            payload=(command+'\n').encode()
            timeout=request.get('timeout_seconds',10)
            if not isinstance(command,str) or '\n' in command or '\r' in command or len(payload)>4096 or not 0<timeout<=60:
                raise ValueError('command requires one bounded line and <=60-second timeout')
            if target not in self.targets:raise ValueError('command launch target is not owned')
            proc,owner=self.targets[target]
            log=self.root/(target+'.private.log')
            self.pending[path.name]={'path':path,'target':target,'payload':payload,'owner':owner,'proc':proc,
                'offset':log.stat().st_size if log.exists() else 0,'deadline':min(now+timeout,path.stat().st_mtime+timeout),
                'response_contains':request.get('response_contains'),'submitted':False}
        for name,pending in list(self.pending.items()):
            result={'request_id':Path(name).stem,'launch_id':pending['target'],'process_identity':pending['owner'],'log_offset':pending['offset']}
            finished=False
            if not alive(pending['owner']):result['status']='target_not_live';finished=True
            elif now>pending['deadline']:result['status']='response_timeout' if pending['submitted'] else 'submission_timeout';finished=True
            elif not pending['submitted']:
                fd=pending['proc'].stdin.fileno();os.set_blocking(fd,False)
                try:
                    count=os.write(fd,pending['payload'])
                    if count!=len(pending['payload']):raise ValueError('partial owned command write')
                    pending['submitted']=True;pending['submitted_at']=now
                except BlockingIOError:continue
                except BrokenPipeError:result['status']='target_stdin_closed';finished=True
            if pending['submitted'] and not finished:
                result['submitted_at']=pending['submitted_at']
                if pending['response_contains'] is None:result['status']='submitted';finished=True
                else:
                    with open(self.root/(pending['target']+'.private.log'),'rb') as log:
                        log.seek(pending['offset']);output=log.read(256*1024).decode(errors='replace')
                    if pending['response_contains'] in output:result['status']='response_observed';finished=True
            if finished:
                write(self.results/name,result);pending['path'].unlink();del self.pending[name]
