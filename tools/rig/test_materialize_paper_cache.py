import bz2
import unittest
from materialize_paper_cache import apply_patch

class PaperPatchTest(unittest.TestCase):
    def patch(self,control,diff,extra,size):
        def number(value):return ((1<<63)|-value if value<0 else value).to_bytes(8,'little')
        block=bz2.compress(b''.join(number(value) for value in control));data=bz2.compress(diff)
        return b'BSDIFF40'+number(len(block))+number(len(data))+number(size)+block+data+bz2.compress(extra)
    def test_difference_extra_and_negative_seek(self):
        patch=self.patch([2,1,-2,2,0,0],bytes([1,1,0,0]),b'!',5)
        self.assertEqual(b'bc!ab',apply_patch(b'abcd',patch))
    def test_declared_size_cannot_be_exceeded(self):
        with self.assertRaises(ValueError):apply_patch(b'ab',self.patch([2,1,0],b'\0\0',b'!',2))
    def test_incomplete_output_rejected(self):
        with self.assertRaises(ValueError):apply_patch(b'ab',self.patch([1,0,0],b'\0',b'',2))

if __name__=='__main__':unittest.main()
