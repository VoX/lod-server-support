"""Stage the exact vendor-indexed assets into a disposable run, never a shared game cache."""
import hashlib
import json
from pathlib import Path
from rig import regular,sha


def stages(asset_cache,metadata_file,client_jar,line):
    metadata_file=regular(metadata_file);metadata=json.loads(metadata_file.read_text())
    if metadata.get('id')!=line:raise ValueError('asset metadata Minecraft line mismatch')
    client_jar=regular(client_jar)
    if hashlib.sha1(client_jar.read_bytes()).hexdigest()!=metadata['downloads']['client']['sha1']:
        raise ValueError('asset metadata does not match selected vendor client jar')
    index=metadata['assetIndex'];identifier=index['id']
    if not isinstance(identifier,str) or Path(identifier).name!=identifier:raise ValueError('unsafe asset index identity')
    path=Path(asset_cache)/'indexes'/(identifier+'.json')
    # Loom namespaces its cache index by Minecraft version; vendor bytes still
    # must match exactly, and the run uses the vendor's actual index identifier.
    if not path.exists():path=Path(asset_cache)/'indexes'/(line+'-'+identifier+'.json')
    path=regular(path)
    if hashlib.sha1(path.read_bytes()).hexdigest()!=index['sha1']:raise ValueError('vendor asset index hash mismatch')
    files={'metadata.json':metadata_file,'indexes/'+identifier+'.json':path}
    for value in json.loads(path.read_text())['objects'].values():
        checksum=value['hash']
        if len(checksum)!=40 or any(c not in '0123456789abcdef' for c in checksum):raise ValueError('invalid vendor asset hash')
        relative='objects/'+checksum[:2]+'/'+checksum
        if relative in files:continue
        source=regular(Path(asset_cache)/relative)
        if source.stat().st_size!=value['size'] or hashlib.sha1(source.read_bytes()).hexdigest()!=checksum:
            raise ValueError('vendor asset object hash/size mismatch')
        files[relative]=source
    hashes={name:sha(source) for name,source in files.items()}
    return identifier,[{'source':str(source.resolve()),'target':'assets/'+name,'sha256':hashes[name]} for name,source in files.items()],hashes
