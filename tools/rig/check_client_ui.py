"""Check run-bound UI exports, exact settings transitions, and inspected captures.

Pixel meaning is inspected by the operator; this checker verifies the raw evidence
and forbids substituting generic assertion booleans for it. No map/render claim.
"""
from pathlib import Path
import json,zlib

ASSERTIONS = ('real_ui_opened', 'setting_applied', 'effective_value_observed',
              'failed_save_feedback', 'parent_binding_refreshed',
              'pending_edit_preserved', 'escape_parent_preserved')
EXPORTS = {'preserved-draft-applied-export.json': False,
           'reception-applied-export.json': True, 'save-failure-export.json': False,
           'final-restored-export.json': True}
CONFIGS = ('parent-return-before-apply-config.json', 'preserved-draft-applied-config.json',
           'canonical-restored-baseline.json', 'save-failure-before.json',
           'save-failure-after.json', 'final-restored-config.json')
SCREENS = ('status-command-readable.png', 'status-entry-open.png',
           'parent-refreshed-draft-preserved.png', 'applied-unsaved-readable.png',
           'final-restored-saved.png', 'escape-parent-return.png')

def verify_png(data):
    """Bounded validation for native RGB/RGBA8, non-interlaced XInput captures."""
    import struct,zlib
    if not data.startswith(b'\x89PNG\r\n\x1a\n'):raise ValueError('invalid screenshot signature')
    offset=8;header=None;compressed=[];ended=False
    while offset<len(data):
        if offset+12>len(data):raise ValueError('truncated PNG chunk')
        size=struct.unpack('>I',data[offset:offset+4])[0];kind=data[offset+4:offset+8]
        end=offset+12+size
        if end>len(data):raise ValueError('truncated PNG payload')
        payload=data[offset+8:offset+8+size];crc=struct.unpack('>I',data[offset+8+size:end])[0]
        if zlib.crc32(kind+payload)&0xffffffff!=crc:raise ValueError('PNG checksum mismatch')
        if header is None:
            if kind!=b'IHDR' or size!=13:raise ValueError('PNG header missing')
            header=struct.unpack('>IIBBBBB',payload);width,height,depth,color,compression,filtering,interlace=header
            if not(320<=width<=4096 and 180<=height<=4096 and depth==8 and color in(2,6) and compression==filtering==interlace==0):raise ValueError('invalid screenshot geometry/format')
        elif kind==b'IHDR':raise ValueError('duplicate PNG header')
        elif kind==b'IDAT':compressed.append(payload)
        elif kind==b'IEND':
            if size or end!=len(data):raise ValueError('trailing PNG data')
            ended=True;break
        offset=end
    if not ended or not compressed:raise ValueError('incomplete PNG image')
    stride=1+width*(3 if color==2 else 4);expected=stride*height
    decoder=zlib.decompressobj();pixels=decoder.decompress(b''.join(compressed),expected+1)
    if len(pixels)!=expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:raise ValueError('invalid PNG pixel stream')
    if any(pixels[offset]>4 for offset in range(0,len(pixels),stride)):raise ValueError('invalid PNG row filter')

def check_report(proof, manifest, scenario, root):
    from rig import read, digest, inside, regular, sha
    no_consumer = scenario.get('execution_route') == 'client-ui-no-consumer'
    if no_consumer:
        route_ok = (scenario.get('id') == 'client-ui-no-consumer'
                    and scenario.get('checker') == 'client-ui-no-consumer'
                    and scenario.get('requires_handshake') is False and proof.get('handshake') is False)
    else:
        route_ok = (scenario.get('id') == 'ui-apply'
                    and scenario.get('checker') in (None, 'client-ui')
                    and scenario.get('execution_route') in (None, 'client-ui')
                    and scenario.get('requires_handshake', True) is True and proof.get('handshake') is True)
    if (not route_ok or type(scenario.get('required_test_count')) is not int
            or scenario['required_test_count'] != 7 or scenario.get('assertions') != list(ASSERTIONS)
            or type(proof.get('test_count')) is not int or proof['test_count'] != 7
            or any(proof.get('assertions', {}).get(key) is not True for key in ASSERTIONS)):
        return ['client UI requires its exact seven-assertion connection route and raw evidence']
    try:
        if root is None:raise ValueError('run evidence required')
        root = Path(root)
        profile, runtime, locked_scenario = [read(root / (name + '.json')) for name in ('profile', 'runtime', 'scenario')]
        for name, data in [('profile', profile), ('runtime', runtime), ('scenario', locked_scenario)]:
            if digest(data) != manifest.get(name + '_hash'):raise ValueError('stale ' + name + ' binding')
        if locked_scenario != scenario:raise ValueError('scenario differs from locked UI route')
        if no_consumer and (profile.get('platform') != 'neoforge' or profile.get('route') != 'native'
                or profile.get('line') not in ('1.21.10', '1.21.11', '26.1', '26.2')):
            raise ValueError('route requires an applicable native NeoForge UI profile')
        if ({row.get('id') for row in runtime.get('launches', [])} != {'server', 'client'}
                or manifest.get('backend') != 'isolated-linux-prism'):
            raise ValueError('owned native client and server runtime required')
        if profile.get('platform') not in ('fabric','neoforge') or profile.get('line') not in ('1.21.1','1.21.10','1.21.11','26.1','26.2'):
            raise ValueError('applicable native client UI line required')
        components = {row['uid']: row['version'] for row in profile.get('components', [])}
        expected_versions = {'MINECRAFT': components['net.minecraft'], 'LOADER': components['net.neoforged' if profile['platform']=='neoforge' else 'net.fabricmc.fabric-loader']}
        evidence = proof.get('evidence', {})
        raw = {}
        for name in (*EXPORTS, *CONFIGS, *SCREENS):
            path = regular(inside(root / 'evidence', name))
            if path.stat().st_size > 1024 * 1024:raise ValueError('oversized UI evidence')
            if sha(path) != evidence.get(name):raise ValueError('missing/changed evidence: ' + name)
            raw[name] = path.read_bytes()
            if name.endswith('.png'):
                verify_png(raw[name])
        if scenario.get('version',1)>=2:
            inspection_path=regular(inside(root/'evidence','ui-actions.json'))
            if sha(inspection_path)!=evidence.get('ui-actions.json'):raise ValueError('visual inspection evidence changed')
            inspection=read(inspection_path)
            if (inspection.get('run_hash')!=manifest['run_hash'] or inspection.get('operator_visual_checked') is not True
                    or inspection.get('screenshots')!={name:evidence[name]for name in SCREENS}):
                raise ValueError('run-bound operator inspection missing')
        start = manifest.get('started_at', manifest.get('created_at'))
        finish = manifest.get('finished_at')
        if not isinstance(start, (int, float)):raise ValueError('run start time missing')
        for name, enabled in EXPORTS.items():
            data = json.loads(raw[name])
            expected_renderer = profile['line']=='1.21.1' or profile['platform']=='fabric'
            common_ok = (type(data.get('schemaVersion')) is int and data['schemaVersion']==1
                         and data.get('connected') is True and data.get('receptionEnabled') is enabled
                         and data.get('rendererAvailable') is expected_renderer)
            if no_consumer:
                connection_ok = (data.get('consumerAvailable') is False and data.get('negotiated') is False
                                 and type(data.get('protocol')) is int and data['protocol']==0
                                 and data.get('serverEnabled') is False and data.get('discovery')=='DORMANT'
                                 and all(type(data.get(key)) is int and data[key]==0 for key in ('receivedColumns','receivedBytes')))
            else:
                connection_ok = (data.get('consumerAvailable') is True and data.get('negotiated') is True
                                 and type(data.get('protocol')) is int and data['protocol']==20
                                 and data.get('serverEnabled') is True)
            if not common_ok or not connection_ok:
                raise ValueError('export does not show the required connected UI state: '+name)
            if any(data.get('versions', {}).get('components', {}).get(key) != value for key, value in expected_versions.items()):
                raise ValueError('export native versions differ from the locked profile: ' + name)
            captured = data.get('capturedAtMillis')
            if (type(captured) is not int or captured < start * 1000
                    or isinstance(finish, (int, float)) and captured > finish * 1000):
                raise ValueError('export is outside the owned run lifetime: ' + name)
        configs = {name: json.loads(raw[name]) for name in CONFIGS}
        baseline = configs['canonical-restored-baseline.json']
        if baseline.get('receiveServerLods') is not True or baseline.get('enableJoinSlowStart') is not True:
            raise ValueError('original restored settings missing')
        before = dict(baseline, receiveServerLods=False)
        if configs['parent-return-before-apply-config.json'] != before:
            raise ValueError('status return changed the pending draft or unrelated settings')
        if configs['preserved-draft-applied-config.json'] != dict(before, enableJoinSlowStart=False):
            raise ValueError('real Apply did not save the preserved draft alone')
        if not (raw['canonical-restored-baseline.json'] == raw['save-failure-before.json']
                == raw['save-failure-after.json'] == raw['final-restored-config.json']):
            raise ValueError('save failure or restoration changed original config bytes')
    except (ValueError, OSError, TypeError, KeyError, AttributeError, zlib.error) as error:
        return ['client UI evidence invalid: ' + str(error)]
    return []
