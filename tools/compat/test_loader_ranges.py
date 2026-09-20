import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from catalog import Invalid
from loader_ranges import evaluate


class LoaderRangeBridgeTests(unittest.TestCase):
    def test_dependency_free_profile_validates_pins_without_launching_java(self):
        with tempfile.TemporaryDirectory() as directory:
            dependency = Path(directory) / 'loader.jar'
            dependency.write_bytes(b'pinned-loader-test-input')
            runtime = {
                'schema_version': 1,
                'java': '/unused/java',
                'classpath': [{'path': str(dependency), 'sha256': hashlib.sha256(dependency.read_bytes()).hexdigest()}],
            }
            with patch('loader_ranges.subprocess.run') as launch:
                self.assertEqual([], evaluate([], runtime))
                launch.assert_not_called()
                dependency.write_bytes(b'changed')
                with self.assertRaises(Invalid):
                    evaluate([], runtime)
                launch.assert_not_called()
