import unittest,base64
from check_xaero_map import target_rgba
from xaero_map_viewport import framed,zoom_button,capture_stable
class NativeMapFixTest(unittest.TestCase):
 def row(self,x=32):
  return dict(chunk_x=x,chunk_z=16,tile_chunk_x=x//4,tile_chunk_z=4,buffer_base64=base64.b64encode(bytes(range(256))*64).decode())
 def test_other_chunk_change_does_not_change_target(self):
  a=self.row();b=dict(a);raw=bytearray(base64.b64decode(a['buffer_base64']));raw[63*4]^=255;b['buffer_base64']=base64.b64encode(raw).decode();self.assertEqual(target_rgba(a),target_rgba(b))
 def test_target_change_is_not_equal(self):
  a=self.row();b=dict(a);raw=bytearray(base64.b64decode(a['buffer_base64']));raw[0]^=255;b['buffer_base64']=base64.b64encode(raw).decode();self.assertNotEqual(target_rgba(a),target_rgba(b))
 def test_left_boundary_uses_last_chunk_rectangle(self):
  a=self.row(31);b=dict(a);raw=bytearray(base64.b64decode(a['buffer_base64']));raw[48*4]^=255;b['buffer_base64']=base64.b64encode(raw).decode();self.assertNotEqual(target_rgba(a),target_rgba(b))
 def test_wrong_group_rejected(self):
  a=self.row();a['tile_chunk_x']=7
  with self.assertRaises(ValueError):target_rgba(a)
 def test_initial_view_excludes_boundary(self):
  self.assertFalse(framed(dict(camera_x=264,camera_z=264,scale=3,width=960,height=540)))
 def test_observed_zoom_frames_both_chunks(self):
  self.assertTrue(framed(dict(camera_x=264,camera_z=264,scale=1.5,width=960,height=540)))
 def test_unreadably_small_target_rejected(self):
  self.assertFalse(framed(dict(camera_x=264,camera_z=264,scale=.1,width=960,height=540)))
 def test_native_button_scaling(self):
  self.assertEqual((410,210),zoom_button(dict(zoom_active=True,zoom_visible=True,zoom_out=[200,100,10,10],gui_width=480,gui_height=270,window_x=0,window_y=0,screen_width=960,screen_height=540)))

 def test_capture_requires_every_unchanged_completed_frame(self):
  base=dict(event='map_viewport',zoom_mouse_over=False,mouse_x=480,mouse_y=135,camera_x=264,camera_z=264,scale=1.5,width=960,height=540)
  rows=[dict(base,time_ns=t,viewport_frame=i+1)for i,t in enumerate((10,20,40,50))]
  receipt=dict(native_viewport=rows[0],confirmed_viewport=rows[-1],capture_started_ns=25,capture_finished_ns=30)
  self.assertTrue(capture_stable(rows,receipt))
  rows[1]['scale']=1.6
  self.assertFalse(capture_stable(rows,receipt))
  rows[1]['scale']=1.5
  self.assertFalse(capture_stable(rows[:1]+rows[2:],receipt))
