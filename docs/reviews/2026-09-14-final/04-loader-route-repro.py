"""Offline reproduction for review 04; temporary ZIP only, never launches Java."""
from pathlib import Path
import json
import sys
import tempfile
import zipfile

ROOT = Path('/home/vox/projects/lss-improvements/1.21.1')
sys.path.insert(0, str(ROOT / 'tools/compat'))
import catalog
import materialize

with tempfile.TemporaryDirectory(prefix='lss-review04-route-') as temporary:
    jar = Path(temporary) / 'neo-only.jar'
    toml = '''modLoader="javafml"
loaderVersion="[1,)"
license="MIT"
[[mods]]
modId="neoonly"
version="1.0"
[[dependencies.neoonly]]
modId="minecraft"
type="required"
versionRange="[1.21.1]"
'''
    with zipfile.ZipFile(jar, 'w') as archive:
        entry = zipfile.ZipInfo('META-INF/neoforge.mods.toml', (1980, 1, 1, 0, 0, 2))
        archive.writestr(entry, toml)
    inspected = catalog.inspect_jar(jar)
    profile = {
        'schema_version': 1, 'id': 'fabric-with-neo', 'line': '1.21.1',
        'platform': 'fabric', 'route': 'native',
        'artifacts': [{
            'id': 'neoonly', 'version': '1.0', 'file': jar.name,
            'sha256': inspected['sha256'],
            'source': 'https://example.invalid/neo-only.jar',
            'metadata': inspected['metadata'], 'enabled': True,
        }],
        'components': [
            {'uid': 'net.minecraft', 'version': '1.21.1'},
            {'uid': 'net.fabricmc.fabric-loader', 'version': '0.19.3'},
        ],
        'capabilities': ['receive'], 'status': 'unverified', 'limitations': [],
    }
    result = materialize.resolution(profile, {inspected['sha256']: str(jar)})
    print(json.dumps({'profile': profile, 'jar_metadata_text': toml, 'result': result}, indent=2))
