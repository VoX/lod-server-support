"""Verify complete immutable dependency trees without treating generated worlds as inputs."""
from rig import inside,regular,sha


def verify(root,trees):
    for directory,expected in trees.items():
        if directory not in ('server/libraries','server/versions','server/cache',
                             'server-replacement/libraries','server-replacement/versions','server-replacement/cache','assets','target-assets'):
            raise ValueError('unsupported immutable runtime tree')
        base=inside(root,directory)
        actual={}
        for path in base.rglob('*'):
            if path.is_symlink():raise ValueError('symlink in immutable runtime tree')
            if path.is_file():actual[str(path.relative_to(base))]=sha(regular(path))
        if actual!=expected:raise ValueError('server dependency tree changed: '+directory)
