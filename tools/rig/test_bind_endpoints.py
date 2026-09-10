import unittest
from bind_endpoints import bindings,check_available
class EndpointsTest(unittest.TestCase):
    def test_all_ports_checked(self):
        seen=[];runtime={'bind_endpoint':'a','additional_bind_endpoints':['b']}
        check_available(runtime,lambda value:(value,1),seen.append)
        self.assertEqual(['a','b'],seen)
    def test_occupied_second_endpoint_fails(self):
        def probe(value):
            if value=='b':raise OSError('occupied')
        with self.assertRaises(OSError):check_available({'bind_endpoint':'a','additional_bind_endpoints':['b']},str,probe)
    def test_bad_or_duplicate_endpoints_fail(self):
        for extra in ('b',[1],['a']):
            with self.assertRaises(ValueError):bindings({'bind_endpoint':'a','additional_bind_endpoints':extra},str)
    def test_existing_single_endpoint_unchanged(self):
        self.assertEqual(['a'],bindings({'bind_endpoint':'a'},str))
