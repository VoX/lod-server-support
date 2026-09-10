#!/usr/bin/env python3
"""Launch a run-owned instance using a separate, persistent authorized account context."""
import argparse,fcntl,json,os,stat,subprocess
from pathlib import Path
from rig import alive,identity,inside,read,write,sha,regular

def verify_launcher_inputs(context,inputs):
    context=Path(context).resolve()
    if not inputs:raise ValueError('missing pinned launcher dependency inputs')
    expected=inputs['files']
    actual=set()
    for directory in ('libraries','assets'):
        for path in (context/directory).rglob('*'):
            if path.is_symlink():raise ValueError('symlink in launcher dependency cache')
            if path.is_file():actual.add(str(path.relative_to(context)))
    expected_cache={p for p in expected if p.startswith(('libraries/','assets/'))}
    if actual!=expected_cache:raise ValueError('launcher dependency closure changed; explicitly acquire then prepare a new run')
    for name,digest in expected.items():
        if not name.startswith(('libraries/','assets/','meta/')):raise ValueError('invalid launcher input scope')
        path=inside(context,name)
        if sha(regular(path))!=digest:raise ValueError('launcher dependency bytes changed: '+name)

def uses_context(argv,cwd,context):
    """Resolve Prism's accepted directory forms without trusting textual spelling."""
    for i,arg in enumerate(argv[1:],1):
        value=None
        if arg in ('--dir','-d') and i+1<len(argv):value=argv[i+1]
        elif arg.startswith('--dir='):value=arg.partition('=')[2]
        elif arg.startswith('-d') and len(arg)>2:value=arg[2:]
        if value is not None:
            path=Path(value)
            if not path.is_absolute():path=Path(cwd)/path
            if path.resolve()==Path(context).resolve():return True
    return False

def prepare_context(context,run,java):
    context=Path(context).resolve();run=Path(run).resolve()
    if context.is_relative_to(run):raise ValueError('account context must be outside disposable run')
    marker=context/'.lss-rig-context.json'
    if not marker.is_file() or read(marker).get('purpose')!='dedicated-rig-launcher':
        raise ValueError('use a separately configured dedicated account context, not a personal launcher')
    if stat.S_IMODE(context.stat().st_mode)&0o077:raise ValueError('account context must be private')
    account=context/'accounts.json'
    if account.is_symlink() or not account.is_file() or stat.S_IMODE(account.stat().st_mode)&0o077:
        raise ValueError('authorized account storage must be a private regular file')
    owner=context/'.lss-active.json'
    if owner.exists() and alive(read(owner)):raise ValueError('authorized context already owned by another live launcher')
    # Refuse a manually opened launcher too. This is a read-only refusal, never
    # termination authority inferred from its command line.
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():continue
        try:
            argv=[part.decode(errors='replace') for part in (proc/'cmdline').read_bytes().split(b'\0') if part]
            if not argv or 'prism' not in Path(argv[0]).name.lower():continue
            cwd=(proc/'cwd').resolve(strict=True)
            if uses_context(argv,cwd,context):
                raise ValueError('preexisting Prism uses the account context; forwarding forbidden')
        except (FileNotFoundError,PermissionError,ProcessLookupError):continue
    instances=inside(run,'instances');instances.mkdir(exist_ok=True)
    # Context is dedicated to rigs. No primary launcher configuration is edited.
    # Prism11.1 Application::createSetupWizard: the owned run already pins Java.
    # https://github.com/PrismLauncher/PrismLauncher/blob/11.1.0/launcher/Application.cpp
    values={'ConfigVersion':'1.3','IgnoreJavaWizard':'true','AutomaticJavaSwitch':'false','AutomaticJavaDownload':'false','UserAskedAboutAutomaticJavaDownload':'true','InstanceDir':str(instances),'JavaPath':str(Path(java).resolve()),'Language':'en_US','ApplicationTheme':'system','IconTheme':'pe_colored','MinMemAlloc':'512','MaxMemAlloc':'3072','LaunchMaximized':'false','MinecraftWinWidth':'960','MinecraftWinHeight':'540','ShowConsole':'false','AutoCloseConsole':'true','ShowConsoleOnError':'false','CloseAfterLaunch':'false','CheckForUpdates':'false','AutoUpdate':'false','PreLaunchCommand':'','PostExitCommand':'','WrapperCommand':'','JvmArgs':''}
    (context/'prismlauncher.cfg').write_text('[General]\n'+''.join(k+'='+v+'\n' for k,v in values.items()))
    write(owner,identity(os.getpid()))
    return context,instances

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--context',required=True,type=Path);p.add_argument('--run',required=True,type=Path);p.add_argument('--java',required=True);p.add_argument('--prism',required=True);p.add_argument('--instance',required=True)
    a=p.parse_args()
    # The outer rig supervisor owns the nested launcher/JVM. This lock prevents
    # two rig runners from changing the maintained context simultaneously.
    lock=a.context/'.lss-context.lock'
    try:
        with lock.open('a') as stream:
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
            inputs=read(a.run/'runtime.json').get('launcher_inputs')
            verify_launcher_inputs(a.context,inputs)
            context,instances=prepare_context(a.context,a.run,a.java)
            if Path(a.instance).name!=a.instance or not (instances/a.instance/'instance.cfg').is_file():raise ValueError('missing run-owned Prism instance')
            env=os.environ.copy();env['QT_QPA_PLATFORM']='xcb'
            process=subprocess.Popen([a.prism,'--dir',str(context),'--launch',a.instance],env=env)
            code=process.wait()
            verify_launcher_inputs(context,inputs)
            (context/'.lss-active.json').unlink(missing_ok=True)
            raise SystemExit(code)
    except (ValueError,OSError) as e:p.exit(1,'rig Prism: '+str(e)+'\n')
if __name__=='__main__':main()
