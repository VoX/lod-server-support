#!/usr/bin/env python3
"""Prepare an immutable named game-test runtime from a captured Loom resolution."""
import argparse
import json
from pathlib import Path
import shutil
import sys
import zipfile


def main():
    parser = argparse.ArgumentParser()
    for option in ('source-worktree', 'capture', 'original-profile', 'java-home', 'output', 'range-runtime'):
        parser.add_argument('--' + option, required=True)
    a = parser.parse_args()
    source, capture, out = Path(a.source_worktree).resolve(), Path(a.capture).resolve(), Path(a.output).resolve()
    sys.path.insert(0, str(source / 'tools/rig'))
    sys.path.insert(0, str(source / 'tools/compat'))
    from rig import sha, write, require_lock
    from catalog import inspect_jar, Invalid
    require_lock()
    properties = dict(line.split('=', 1) for line in (source / 'gradle.properties').read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))
    if properties.get('minecraft_version', '').strip() != '1.21.11':
        raise ValueError('named game-test source must target Minecraft 1.21.11')
    release = dict(line.split('=', 1) for line in (Path(a.java_home) / 'release').read_text().splitlines() if '=' in line)
    if not release.get('JAVA_VERSION', '').strip(chr(34)).startswith('21.'):
        raise ValueError('the named C2ME profile requires the selected Java 21 runtime')
    original = json.loads(Path(a.original_profile).read_text())
    if original['line'] != '1.21.11' or len(original['artifacts']) != 1:
        raise ValueError('expected exact single-artifact 1.21.11 C2ME input profile')
    c2me = original['artifacts'][0]
    if c2me['sha256'] not in ('aacecc703d0efdff9a54fb0be6fd3b4d33724ee46576b5401d33ce18156f0bf1', '232a1a25196b7e704abca3f278d3fc4cf4f5f70d56019d4e668a5750fc86c2d6'):
        raise ValueError('unapproved C2ME artifact')
    originals = json.loads((capture / 'original-mod-inputs.json').read_text())
    selected = [Path(row['path']) for row in originals if row['sha256'] == c2me['sha256']]
    if len(selected) != 1 or sha(selected[0]) != c2me['sha256']:
        raise ValueError('captured resolution does not bind exact original C2ME bytes')
    out.mkdir(parents=True, exist_ok=False, mode=0o700)
    cache = out / 'cache'; cache.mkdir()
    profile = dict(original, id=original['id'] + '-named-gametest-v2', artifacts=[], status='unverified',
                   components=[{'uid': 'net.minecraft', 'version': '1.21.11'}, {'uid': 'net.fabricmc.fabric-loader', 'version': '0.19.3'}, {'uid': 'java', 'version': '21'}],
                   limitations=['Named Loom game-test runtime; exact source C2ME artifact and transformed dependency bytes retained. No external client transport or visual validation is claimed.'],
                   mapping_namespace='named', original_profile_id=original['id'])
    profile['limitations'].extend(line.strip() for line in (capture / 'gradle.log').read_text().splitlines() if 'C2ME dev runtime: omit optional ' in line)
    runtime = dict(backend='linux-headless', bind_endpoint='127.0.0.1:25574', client_endpoint='127.0.0.1:25574',
                   cache={}, candidate_artifacts=[], stage_files=[], generated_files={}, launches=[],
                   range_runtime=json.loads(Path(a.range_runtime).read_text()))
    mapping = {}
    def snapshot(paths, label, candidate=False, enabled=True):
        dest = cache / (label + '.jar')
        if len(paths) == 1 and paths[0].is_file():
            shutil.copyfile(paths[0], dest)
        else:
            with zipfile.ZipFile(dest, 'w') as archive:
                seen = set()
                for directory in paths:
                    for file in sorted(directory.rglob('*')):
                        if file.is_symlink(): raise ValueError('symlink source input')
                        if file.is_file():
                            name = str(file.relative_to(directory))
                            if name in seen: raise ValueError('duplicate class/resource input: ' + name)
                            seen.add(name)
                            archive.writestr(zipfile.ZipInfo(name), file.read_bytes())
        checksum = sha(dest)
        try: metadata = inspect_jar(dest)['metadata']
        except Invalid as error:
            if 'no recognized mod metadata' not in str(error): raise
            metadata = {}
        artifact = dict(id=label, version='locked', file=dest.name, sha256=checksum,
                        source='local-cache:sha256:' + checksum, metadata=metadata,
                        enabled=enabled, kind='mod' if metadata else 'library')
        if candidate: runtime['candidate_artifacts'].append(artifact)
        else: profile['artifacts'].append(artifact)
        runtime['cache'][checksum] = str(dest)
        if candidate or not enabled: runtime['stage_files'].append(dict(source=str(dest), target='artifacts/' + dest.name, sha256=checksum))
        staged = '{run}/artifacts/' + dest.name
        for path in paths: mapping[str(path)] = staged
        return staged
    input_path = snapshot(selected, 'original-c2me-transformation-input', enabled=False)
    profile['artifacts'][-1]['source'] = c2me['source']
    profile['artifacts'][-1]['version'] = c2me['version']
    rows = json.loads((capture / 'runtime-classpath.json').read_text())
    paths = []
    for row in rows:
        path = Path(row['path'])
        if row['directory']:
            actual = {str(file.relative_to(path)): sha(file) for file in path.rglob('*') if file.is_file()}
            if actual != row['files']: raise ValueError('captured class/resource directory changed')
        elif sha(path) != row['sha256']: raise ValueError('captured runtime artifact changed')
        paths.append(path)
    if not any(path.name == 'fabric-loader-0.19.3.jar' for path in paths):
        raise ValueError('captured runtime does not contain the declared Fabric Loader pin')
    common_origin = json.loads((capture / 'named-common.json').read_text())
    common_path = Path(common_origin['path'])
    if common_path != source / 'common/build/libs' / ('common-' + properties['mod_version'].strip() + '.jar') or sha(common_path) != common_origin['sha256']:
        raise ValueError('captured current common output origin changed')
    common_staged = snapshot([common_path], 'lss-common-named', candidate=True)
    runtime['named_project_components'] = {'common': {'origin':str(common_path),'sha256':sha(common_path),'target':'artifacts/lss-common-named.jar'}}
    groups = {}
    supplements = []
    for flavor in ('main', 'gametest'):
        group = [path for path in paths if path == source / ('fabric/build/classes/java/' + flavor) or path == source / ('fabric/build/resources/' + flavor)]
        if len(group) != 2: raise ValueError('missing exact classes/resources group: ' + flavor)
        if flavor == 'main':
            candidate = json.loads((capture / 'named-candidate.json').read_text())
            candidate_path = Path(candidate['path'])
            if sha(candidate_path) != candidate['sha256']:
                raise ValueError('named packaged candidate changed since capture')
            staged = snapshot([candidate_path], 'lss-main-named', candidate=True)
            runtime['named_project_components']['main']={'origin':str(candidate_path),'sha256':sha(candidate_path),'target':'artifacts/lss-main-named.jar'}
            for path in group: mapping[str(path)] = staged
            classes = next(path for path in group if '/classes/' in str(path))
            with zipfile.ZipFile(candidate_path) as packaged:
                missing = [file for file in classes.rglob('*.class') if str(file.relative_to(classes)) not in packaged.namelist()]
            if any(not str(file.relative_to(classes)).startswith('dev/vox/lss/benchmark/') for file in missing):
                raise ValueError('unexpected non-benchmark class absent from named package')
            if missing:
                support = out / 'dev-test-support.jar'
                with zipfile.ZipFile(support, 'w') as archive:
                    for file in sorted(missing):
                        archive.writestr(zipfile.ZipInfo(str(file.relative_to(classes))), file.read_bytes())
                supplements.append(snapshot([support], 'lss-dev-test-support', candidate=True))
        else:
            staged = snapshot(group, 'lss-' + flavor + '-named', candidate=True)
        for path in group: groups[path] = staged
    classpath = list(supplements)
    for index, path in enumerate(paths):
        common_dirs = (source/'common/build/classes/java/main',source/'common/build/resources/main')
        staged = common_staged if path in common_dirs or path==common_path else groups.get(path) or snapshot([path], 'runtime-%03d' % index)
        if path in common_dirs:mapping[str(path)]=common_staged
        if staged not in classpath: classpath.append(staged)
    remap = capture / 'remap-classpath.txt'
    remapped_paths = []
    for index, raw in enumerate(remap.read_text().strip().split(':')):
        path = Path(raw)
        remapped_paths.append(mapping.get(raw) or snapshot([path], 'remap-%03d' % index, enabled=False))
    runtime['generated_files']['remap-classpath.txt'] = ':'.join(remapped_paths)
    runtime['generated_files']['server/eula.txt'] = 'eula=true\n'
    runtime['generated_files']['server/config/lss-server-config.json'] = json.dumps(dict(lodStore='off', lodStoreBackfill=False, maxConcurrentDiskReads=64))
    scenario = json.loads((source / 'tools/rig/scenarios/c2me-save-read.json').read_text())
    argv = [str(Path(a.java_home) / 'bin/java'), '-Xmx2G', '-Dfabric.development=true',
            '-Dfabric.defaultModDistributionNamespace=intermediary', '-Dfabric.defaultMixinRemapType=mixin',
            '-Dfabric.remapClasspathFile={run}/remap-classpath.txt', '-Dfabric-api.gametest',
            '-Dfabric-api.gametest.report-file={run}/evidence/gametests.xml', '-Dlss.test.integratedServer=true',
            '-Dfabric-api.gametest.filter=' + scenario['native_filter'],
            '-cp', ':'.join(classpath), 'net.fabricmc.loader.impl.launch.knot.KnotServer', '--nogui']
    runtime['generated_files']['gametest-command.json'] = json.dumps(dict(argv=argv, c2me_version=c2me['version']))
    wrapper = source / 'tools/rig/run_server_gametests.py'
    runtime['stage_files'].append(dict(source=str(wrapper), target='run_server_gametests.py', sha256=sha(wrapper)))
    runtime['launches'] = [dict(id='gametests', cwd='server', argv=[sys.executable, '{run}/run_server_gametests.py', '{run}', str(source / 'tools/rig')])]
    for file in sorted((source / 'scripts/soak-scenarios').glob('*.json')):
        runtime['stage_files'].append(dict(source=str(file), target='scripts/soak-scenarios/' + file.name, sha256=sha(file)))
    for name in ('manifest.json', 'runtime-classpath.json', 'original-mod-inputs.json', 'named-candidate.json', 'named-common.json'):
        file = capture / name
        runtime['stage_files'].append(dict(source=str(file), target='evidence/captured-' + name, sha256=sha(file)))
    write(out / 'profile.json', profile); write(out / 'runtime.json', runtime); write(out / 'scenario.json', scenario)
    write(out / 'captured-inputs.json', dict(classpath=rows, original_mod_inputs=originals))
    print(out)


if __name__ == '__main__': main()
