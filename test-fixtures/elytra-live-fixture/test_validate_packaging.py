import json,tempfile,unittest,zipfile
from pathlib import Path
from validate_packaging import validate
class PackagingTest(unittest.TestCase):
 def jar(self,base,helper):
  p=Path(base)/'fixture.jar'
  with zipfile.ZipFile(p,'w') as z:
   z.writestr('fabric.mod.json',json.dumps({'mixins':['m.json'],'entrypoints':{'client':['fixture.Consumer']}}))
   z.writestr('m.json',json.dumps({'package':'fixture.mixin','client':['Tick']}))
   z.writestr('fixture/mixin/Tick.class',b'Lorg/spongepowered/asm/mixin/Mixin;')
   z.writestr(helper,b'ordinary class')
  return p
 def test_separate_helpers_allowed(self):
  with tempfile.TemporaryDirectory() as t:self.assertTrue(validate(self.jar(t,'fixture/Consumer.class')))
 def test_reserved_subpackage_helper_rejected(self):
  with tempfile.TemporaryDirectory() as t:
   with self.assertRaisesRegex(ValueError,'ordinary helper'):validate(self.jar(t,'fixture/mixin/helper/Consumer.class'))
 def test_reserved_sibling_helper_rejected(self):
  with tempfile.TemporaryDirectory() as t:
   with self.assertRaisesRegex(ValueError,'ordinary helper'):validate(self.jar(t,'fixture/mixin/Consumer.class'))
if __name__=='__main__':unittest.main()
