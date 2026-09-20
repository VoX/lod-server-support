import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from assets import stages


class AssetClosureTests(unittest.TestCase):
    def test_vendor_index_and_object_bytes_both_bound(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);client=root/'client.jar';client.write_bytes(b'client')
            checksum=hashlib.sha1(b'asset').hexdigest();asset=root/'objects'/checksum[:2]/checksum
            asset.parent.mkdir(parents=True);asset.write_bytes(b'asset')
            index=root/'indexes/test.json';index.parent.mkdir();index.write_text(json.dumps({'objects':{'sound':{'hash':checksum,'size':5}}}))
            metadata=root/'version.json';metadata.write_text(json.dumps({'id':'26.2','downloads':{'client':{'sha1':hashlib.sha1(b'client').hexdigest()}},'assetIndex':{'id':'test','sha1':hashlib.sha1(index.read_bytes()).hexdigest()}}))
            identifier,files,hashes=stages(root,metadata,client,'26.2')
            self.assertEqual('test',identifier);self.assertEqual(3,len(files));self.assertEqual(3,len(hashes))
            asset.write_bytes(b'wrong')
            with self.assertRaisesRegex(ValueError,'object hash'):stages(root,metadata,client,'26.2')
            asset.write_bytes(b'asset');index.write_text('{}')
            with self.assertRaisesRegex(ValueError,'index hash'):stages(root,metadata,client,'26.2')
