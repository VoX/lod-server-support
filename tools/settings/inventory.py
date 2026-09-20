#!/usr/bin/env python3
"""Generate explicit base metadata/bindings; RuntimeSettings and ClientOptionCatalog own controls."""
import argparse
import json
import pathlib
import re
ROOT = pathlib.Path(__file__).resolve().parents[2]
INPUTS = {
 'Server': 'common/src/main/java/dev/vox/lss/common/config/ServerConfigBase.java',
 'Client': 'xplat/src/main/java/dev/vox/lss/config/LSSClientConfig.java',
 'Paper': 'paper/src/main/java/dev/vox/lss/paper/PaperConfig.java',
}
DOMAINS = {
 'lodDistanceChunks': '[1,2048]; clampLodDistance',
 'lodDistanceChunksByWorld': 'fresh map replacement; each distance [1,2048]; removal uses dimension/global fallback',
 'mbPerSecondLimitPerPlayer': 'negative missing-key sentinel adopts legacy bytes, else 25 MiB/s; nonnegative clamp [1/1024,1024] MiB/s',
 'mbPerSecondLimitGlobal': 'negative missing-key sentinel adopts legacy bytes, else 75 MiB/s; nonnegative clamp [1/1024,1024] MiB/s',
 'bytesPerSecondLimitPerPlayer': 'legacy input: >=0 adopted only if MiB key is missing; then reset to -1 and hidden on save',
 'bytesPerSecondLimitGlobal': 'legacy input: >=0 adopted only if MiB key is missing; then reset to -1 and hidden on save',
 'diskReaderThreads': '<=0 AUTO; positive [1,64]; effective AUTO depends on read-priority support and CPU cores',
 'maxConcurrentDiskReads': '<=0 AUTO; positive [1,64], effective capped at reader pool; AUTO ceil(pool/2) with attached store, else pool',
 'sendQueueLimitPerPlayer': '[1,100000]',
 'generationConcurrencyLimitGlobal': '[1,512]; clampGenGlobal',
 'generationConcurrencyLimitPerPlayer': '[1, configured generationConcurrencyLimitGlobal]; clampGenPerPlayer',
 'generationTimeoutSeconds': '[1,600]',
 'dirtyBroadcastIntervalSeconds': '<=0 disables dirty pushes, retains invalidation drain; positive [1,300]',
 'perDimensionTimestampCacheSizeMB': '<=0 AUTO derived from distance; positive [1,512]; effective allocation fixed at service creation',
 'missMemoTtlSeconds': '[0,60]; 0 disables memo',
 'lodStore': 'on | off; legacy full aliases on; memory/unknown/null -> off; new-install ON, upgraded missing-key OFF',
 'lodStoreResweepSeconds': '[0,3600]; 0 disables periodic resweep',
 'lodStoreBackfillColumnsPerSecond': '[10,1000]',
 'lodStoreMaxMB': '<=0 uncapped; positive [64,1048576]',
 'xrayObfuscation': 'auto | on | off; normalized by XrayMaskPolicy; invalid -> auto',
 'xrayHiddenBlocks': 'block identifiers; null restores default hidden-block list; resolver validates identifiers',
 'xrayMaxBlockHeight': '[-2048,2048]',
 'farPlayers': 'off | opt-in | on; optin/opt_in aliases opt-in; null/unknown -> off',
 'farPlayersUpdateIntervalTicks': '[2,100]',
 'farPlayersMaxDistanceBlocks': '[128,16384]',
 'farPlayersMinDistanceBlocks': '[0,16384], capped at configured maximum; 0 no inner ring',
 'farPlayersExclude': 'player names/UUID strings consumed by existing filter; null -> empty list; identifying, never exported',
 'updateEvents': 'Bukkit event class names; null -> empty list; unavailable/unrecognized classes skipped',
 'cacheAddressAliases': 'ordered address groups; null -> empty; invalid groups retained on disk but ignored effectively; identifying, never exported',
 'unknownBlockFallback': 'block identifier; null/blank -> minecraft:stone; runtime block resolver validates resolution',
 'crossVersionBlockFallbacks': 'block identifier mapping; null -> empty; null/blank entries dropped; runtime resolver validates resolution',
 'lodColumnsPerSecondLimit': '<=0 unlimited; positive [10,100000]; Sodium slider is a curved index over RateSliderStops',
 'farPlayersShareDistanceBlocks': '[0,16384]; 0 no additional client sharing cap',
 'farPlayersMaxRenderDistanceBlocks': '[0,16384]; 0 uses configured far-player ring',
 'farPlayersMaxAnimationDistanceBlocks': '[0,16384]; 0 never animate',
}

def uncomment(source):
 return re.sub(r'("(?:\\.|[^"\\])*")|//[^\n]*|/\*.*?\*/', lambda m: m.group(1) or '', source, flags=re.S)

def fields(side):
 source = uncomment((ROOT / INPUTS[side]).read_text())
 for match in re.finditer(r'^    public (?!static|transient)([\w.<> ,]+?) (\w+)\s*=\s*([^;]+);', source, re.M):
  kind,key,default=match.groups()
  if '(' in kind or '\n' in kind: continue
  yield kind.removeprefix('volatile '),key,' '.join(default.split())

def metadata(side):
 rows=[]
 for kind,key,default in fields(side):
  if kind in ('boolean','int','long','double','String'):
   typ={'boolean':'BOOLEAN','int':'INTEGER','long':'INTEGER','double':'DECIMAL','String':'STRING'}[kind]
  elif re.fullmatch(r'(?:java\.util\.)?Map<.+>',kind): typ='MAP'
  elif re.fullmatch(r'(?:java\.util\.)?List<.+>',kind): typ='LIST'
  else: raise ValueError(f'unclassified serialized type: {side}.{key}: {kind}')
  unit='boolean' if typ=='BOOLEAN' else 'blocks' if 'Blocks' in key or key=='xrayMaxBlockHeight' else 'chunks' if key.startswith('lodDistanceChunks') else 'seconds' if 'Seconds' in key else 'ticks' if 'Ticks' in key else 'MiB' if key.endswith('MB') else 'MiB/s raw' if key.startswith('mbPerSecond') else 'bytes/s raw' if key.startswith('bytesPerSecond') else 'columns/s' if 'PerSecond' in key else 'count' if typ=='INTEGER' else 'identifiers' if typ in ('LIST','MAP') else 'enum or identifier'
  domain='true | false' if typ=='BOOLEAN' else DOMAINS[key]
  if side=='Client' and key=='lodDistanceChunks': domain='[0,2048]; 0 server default; effective capped by negotiated server distance'
  if side=='Client' and key=='farPlayersMaxDistanceBlocks': domain='[0,16384]; 0 uses server maximum'
  if side=='Client' and key=='farPlayersMinDistanceBlocks': domain='[0,16384]; capped by positive client maximum, else 16384; 0 server-controlled inner ring'
  if key=='lodStore': default='new install on; existing file missing key off; legacy full adopted as on'
  if key=='lodStoreResweepSeconds': default='Fabric/Neo 0; Paper/Folia 300 seconds (constructor override)'
  if key=='mbPerSecondLimitPerPlayer': default='25 MiB/s; legacy byte key adopted when new key missing'
  if key=='mbPerSecondLimitGlobal': default='75 MiB/s; legacy byte key adopted when new key missing'
  scopes=['CLIENT' if side=='Client' else 'SERVER']
  inheritance=side.lower()+'-global'
  exposure='INTERNAL' if key.startswith('bytesPerSecond') else 'ADVANCED'
  timing='config file load; restart required'; restart=True; control='config file load'
  availability='Paper/Folia only' if side=='Paper' else 'all client platforms' if side=='Client' else 'all server platforms'
  if key.startswith('lodStoreBackfill'): availability='Fabric/Neo store path only; accepted but inert on Paper/Folia'
  if key in ('useBackgroundReadSplit','useSelectiveNbtParse','useNbtTranscode'): availability='platform read path supports the optimization; otherwise existing fallback'
  if key=='lodDistanceChunksByWorld':
   scopes=['SERVER','WORLD_DISTANCE']; inheritance='Paper: world name, dimension, global; Fabric/Neo: dimension, global'
   exposure='RUNTIME'; timing='owner apply; session config repush; boot-sized cache allocation unchanged';restart=False
   control='RuntimeSettings.applyWithPersistenceOutcome with lodDistanceChunks (explicit world argument)'
  validator={'Server':'ServerConfigBase','Client':'LSSClientConfig','Paper':'PaperConfig'}[side]+'.validate'
  rows.append(dict(key=key,type=typ,units=unit,documentationKey=side.lower()+'.'+key,defaultPolicy=default,domain=domain,validationBinding=validator,scopes=scopes,inheritance=inheritance,availability=availability,applyTiming=timing,restartRequired=restart,controlAction=control,exposure=exposure))
 return rows

def generate(side):
 rows=[];bindings=[]
 for i,d in enumerate(metadata(side)):
  args=[json.dumps(d['key']), 'SettingDescriptor.Type.'+d['type']]+[json.dumps(d[k]) for k in ['units','documentationKey','defaultPolicy','domain','validationBinding']]
  args+=['java.util.Set.of('+', '.join('SettingDescriptor.Scope.'+v for v in d['scopes'])+')']
  args += [json.dumps(d[k]) for k in ['inheritance','availability','applyTiming']]
  args += [str(d['restartRequired']).lower(),json.dumps(d['controlAction']),'SettingDescriptor.Exposure.'+d['exposure']]
  rows.append('            new SettingDescriptor('+', '.join(args)+')')
  getter='generationConfiguredForRestart()' if d['key']=='enableChunkGeneration' else d['key']
  bindings.append(f'                new SettingBinding<>(descriptors.get({i}), config -> config.{getter})')
 name=side+'SerializedSettings'
 text='package dev.vox.lss.common.config;\n\n/** Generated by tools/settings/inventory.py; existing typed controls override exposure. */\npublic final class '+name+' {\n    public static java.util.List<SettingDescriptor> descriptors() {\n        return java.util.List.of(\n'+',\n'.join(rows)+'\n        );\n    }\n'
 if side=='Server':text+='    public static java.util.List<SettingBinding<ServerConfigBase>> bindings() {\n        var descriptors = descriptors();\n        return java.util.List.of(\n'+',\n'.join(bindings)+'\n        );\n    }\n'
 return text+'    private '+name+'() {}\n}\n'
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
 for side in INPUTS:
  target=ROOT/f'common/src/main/java/dev/vox/lss/common/config/{side}SerializedSettings.java';content=generate(side)
  if args.check:
   if not target.exists() or target.read_text()!=content: parser.exit(1,f'{target.name} is stale: run tools/settings/inventory.py\n')
  else:target.write_text(content)
