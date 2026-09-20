"""Explicit native save setup evidence; no delivery or save-completion claim."""
import uuid

KEY = 'native_save_setup'
PROPERTY = '-Dlss.rig.nativeAutoSaveInterval'
FLAG = PROPERTY + '='
SETUP = {'schema_version': 1, 'variant': 'native-autosave-200-cap24',
         'interval_ticks': 200, 'max_chunks_per_tick': 24}
CONFIG = 'server/config/paper-world-defaults.yml'
CONTENT = '_version: 31\nchunks:\n  auto-save-interval: 200\n  max-auto-save-chunks-per-tick: 24\n  flush-regions-on-save: false\n'


def property_token(token):
    return token == PROPERTY or token.startswith(FLAG)


def requested(scenario, runtime):
    return KEY in scenario or any(property_token(token) for launch in runtime.get('launches', [])
        for token in launch.get('argv', []))


def inputs(scenario, runtime):
    """Exact opt-in. Historical absent variants require no new evidence."""
    if not requested(scenario, runtime):
        return []
    errors = []
    setup = scenario.get(KEY)
    if (setup != SETUP or not isinstance(setup, dict)
            or any(type(setup.get(k)) is not int for k in ('schema_version', 'interval_ticks', 'max_chunks_per_tick'))):
        errors.append('native save setup schema/value mismatch')
    if scenario.get('server_platform') not in ('paper', 'folia') or scenario.get('checker') != 'concurrent-sources':
        errors.append('native save setup platform/checker mismatch')
    flags = [(launch.get('id'), token) for launch in runtime.get('launches', [])
             for token in launch.get('argv', []) if property_token(token)]
    if flags != [('server', FLAG + '200')]:
        errors.append('native save setup requires one exact server flag')
    if runtime.get('generated_files', {}).get(CONFIG) != CONTENT:
        errors.append('native save setup generated config mismatch')
    if any(row.get('target') == CONFIG for row in runtime.get('stage_files', [])):
        errors.append('native save setup config has competing stage input')
    return errors


def check(scenario, runtime, events, run_id):
    if not requested(scenario, runtime):
        return []
    errors = inputs(scenario, runtime)
    rows = [row for row in events if row.get('event') == 'native_save_setup']
    if len(rows) != 1:
        return errors + ['native save setup witness absent/repeated']
    row = rows[0]
    if row.get('run_id') != run_id:
        errors.append('native save setup witness belongs to another run')
    if any(type(row.get(k)) is not int or row[k] != SETUP[k]
           for k in ('interval_ticks', 'max_chunks_per_tick')):
        errors.append('native save setup resolved native values mismatch')
    if row.get('dimension') != 'minecraft:overworld' or not isinstance(row.get('world_name'), str) or not row['world_name']:
        errors.append('native save setup world mismatch')
    try:
        if str(uuid.UUID(row['world_uuid'])) != row['world_uuid']:
            raise ValueError('noncanonical UUID')
    except (KeyError, ValueError, TypeError, AttributeError):
        errors.append('native save setup world UUID absent/invalid')
    kind = 'owning-region' if scenario.get('server_platform') == 'folia' else 'server-thread'
    if (row.get('owner_kind') != kind or row.get('owns_region') is not True
            or not isinstance(row.get('owner_identity'), str) or not row['owner_identity']):
        errors.append('native save setup native ownership absent')
    observed, at = row.get('observed_ns'), row.get('time_ns')
    ready = [r.get('time_ns') for r in events if r.get('event') == 'source_preconditions_ready']
    starts = [r.get('time_ns') for r in events if r.get('event') in ('workload_start', 'workload_started')]
    if (type(observed) is not int or type(at) is not int or not 0 < observed <= at
            or len(ready) != 1 or type(ready[0]) is not int or at > ready[0]
            or any(type(t) is not int or at > t for t in starts)):
        errors.append('native save setup witness outside setup interval')
    if any(r.get('event') == 'native_save_setup_failed' for r in events):
        errors.append('native save setup producer failed')
    return errors
