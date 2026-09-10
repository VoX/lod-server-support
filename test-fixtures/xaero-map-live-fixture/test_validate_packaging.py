import json,tempfile,unittest,zipfile
from pathlib import Path
from validate_packaging import validate
class PackagingTest(unittest.TestCase):
 def jar(self,base,loader,helper='fixture/Probe.class',declared=True):
  p=Path(base)/'fixture.jar'
  with zipfile.ZipFile(p,'w')as z:
   if loader=='fabric':z.writestr('fabric.mod.json',json.dumps({'mixins':['m.json'],'entrypoints':{'client':['fixture.Probe']}}))
   else:z.writestr('META-INF/neoforge.mods.toml','modLoader="lowcodefml"\n[[mixins]]\nconfig="m.json"\n')
   z.writestr('m.json',json.dumps({'package':'fixture.mixin','client':['Tick']}))
   z.writestr('fixture/mixin/Tick.class',b'Lorg/spongepowered/asm/mixin/Mixin;' if declared else b'ordinary')
   z.writestr(helper,b'ordinary')
  return p
 def test_fabric_and_neo_separate_helper(self):
  for loader in ['fabric','neo']:
   with self.subTest(loader=loader),tempfile.TemporaryDirectory()as t:self.assertTrue(validate(self.jar(t,loader)))
 def test_both_loaders_reject_reserved_helper(self):
  for loader in ['fabric','neo']:
   for helper in ['fixture/mixin/Probe.class','fixture/mixin/helper/Probe.class']:
    with self.subTest(loader=loader,helper=helper),tempfile.TemporaryDirectory()as t:
     with self.assertRaisesRegex(ValueError,'ordinary helper'):validate(self.jar(t,loader,helper))
 def test_missing_annotation_rejected_both_loaders(self):
  for loader in ['fabric','neo']:
   with self.subTest(loader=loader),tempfile.TemporaryDirectory()as t:
    with self.assertRaisesRegex(ValueError,'lacks Mixin'):validate(self.jar(t,loader,declared=False))
 def test_missing_loader_registration_rejected(self):
  with tempfile.TemporaryDirectory()as t:
   p=Path(t)/'empty.jar'
   with zipfile.ZipFile(p,'w')as z:z.writestr('m.json','{}')
   with self.assertRaisesRegex(ValueError,'no declared'):validate(p)
if __name__=='__main__':unittest.main()
