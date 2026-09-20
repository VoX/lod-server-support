import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('artifacts', Path(__file__).with_name('check_artifacts.py'))
artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(artifacts)


def jar(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data in files.items():
            archive.writestr(name, data)
    return output.getvalue()


class ExclusionTest(unittest.TestCase):
    def test_nested_fixture_and_inner_class_are_rejected(self):
        data = jar({'META-INF/jars/common.jar': jar({'dev/vox/Fixture$Callback.class': b'x'})})
        self.assertEqual(['META-INF/jars/common.jar!/dev/vox/Fixture$Callback.class'],
                         artifacts.violations(data, {'dev/vox/Fixture'}))

    def test_production_class_is_allowed(self):
        self.assertEqual([], artifacts.violations(jar({'dev/vox/Production.class': b'x'}), {'dev/vox/Fixture'}))


if __name__ == '__main__':
    unittest.main()
