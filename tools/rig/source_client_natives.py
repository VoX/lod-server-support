"""Own each four-source client's LWJGL extracted libraries inside its run.

The shipped LWJGL Configuration.SHARED_LIBRARY_EXTRACT_PATH property is verified
against the actual runtime library. This removes a shared native-file surface;
it does not assert the cause of any previously observed native crash.
"""
from pathlib import Path

PREFIX='-Dorg.lwjgl.system.SharedLibraryExtractPath='
SUBJECT_PREFIX='-Dlss.rig.subject='
SUBJECTS=tuple('RigSubject'+letter for letter in 'ABCD')

def expected(subject):
 if subject not in SUBJECTS:raise ValueError('unknown four-source client subject')
 return PREFIX+'{run}/clients/'+subject+'/lwjgl-native'

def subject_of(launch):
 values=[arg[len(SUBJECT_PREFIX):] for arg in launch.get('argv',[]) if arg.startswith(SUBJECT_PREFIX)]
 if not values:return None
 if len(values)!=1:raise ValueError('ambiguous source-client subject')
 return values[0]

def apply(runtime):
 clients=[x for x in runtime['launches'] if x['id'].startswith('client-')]
 if {x['id'] for x in clients}!={'client-'+letter for letter in 'ABCD'} or len(clients)!=4:
  raise ValueError('exactly four source clients required')
 for launch in clients:
  subject=subject_of(launch)
  if subject!='RigSubject'+launch['id'][-1]:raise ValueError('client identity differs from source subject')
  flags=[arg for arg in launch['argv'] if arg.startswith(PREFIX)]
  if flags and flags!=[expected(subject)]:raise ValueError('unowned/duplicate LWJGL extraction override')
 for launch in clients:
  if expected(subject_of(launch)) not in launch['argv']:launch['argv'].insert(1,expected(subject_of(launch)))
 return runtime

def prepare_directory(root,launch):
 subject=subject_of(launch)
 if subject not in SUBJECTS:return None
 if launch.get('id')!='client-'+subject[-1]:raise ValueError('source-client launch identity mismatch')
 flags=[arg for arg in launch['argv'] if arg.startswith(PREFIX)]
 if flags!=[expected(subject)]:raise ValueError('source-client requires its owned native extraction directory')
 root=Path(root).resolve();directory=root
 # Check every ancestor; neither escaping nor an internal symlink is accepted.
 for part in ('clients',subject,'lwjgl-native'):
  directory=directory/part
  if directory.is_symlink():raise ValueError('symlink in owned native extraction path')
  if directory.exists() and not directory.is_dir():raise ValueError('owned native extraction ancestor is not a directory')
  directory.mkdir(exist_ok=True)
 return directory
