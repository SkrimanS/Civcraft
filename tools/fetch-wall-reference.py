"""Fetch and verify the original wall geometry, lifecycle, markers and numeric materials."""
from pathlib import Path
from urllib.request import urlopen
import hashlib
import json

root = Path('tools/legacy-reference')
tree = json.loads((root / 'original-tree.json').read_text(encoding='utf-8'))['tree']
names = {'Wall.java', 'FortifiedWall.java', 'WallBlock.java',
         'MarkerPlacementManager.java', 'CivData.java', 'BuildCommand.java',
         'Town.java', 'Structure.java', 'Buildable.java', 'CivSettings.java',
         'BlockListener.java', 'StructureBlock.java', 'StructureBlockHitEvent.java'}
for entry in tree:
    path = entry['path']
    if '/src/' not in path or Path(path).name not in names:
        continue
    target = root / Path(path).name
    if not target.exists():
        with urlopen('https://raw.githubusercontent.com/ataranlen/civcraft/1.8-Beta-6/' + path,
                     timeout=30) as response:
            target.write_bytes(response.read())
    data = target.read_bytes()
    actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if actual != entry['sha']:
        raise RuntimeError('Cached source differs from original tree: ' + str(target))
    print(path + ' ' + actual)
