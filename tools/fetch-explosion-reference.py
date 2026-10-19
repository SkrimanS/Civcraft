"""Fetch/hash-check original A02 handlers; inspect event references without running the JAR."""
from pathlib import Path
from urllib.request import urlopen
import hashlib
import json
import zipfile

root = Path('tools/legacy-reference')
tree = json.loads((root / 'original-tree.json').read_text(encoding='utf-8'))['tree']
names = {'BlockListener.java', 'WarListener.java', 'WarRegen.java', 'Wall.java',
         'TownHall.java', 'Buildable.java', 'CivCraft.java'}
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

jar = Path('../../server/plugins/MineTexas-CivilizationCraft-1.8.0-beta6.jar')
if jar.is_file():
    with zipfile.ZipFile(jar) as archive:
        for event in (b'EntityExplodeEvent', b'BlockExplodeEvent'):
            references = [name for name in archive.namelist()
                          if name.endswith('.class') and event in archive.read(name)]
            print(event.decode() + ': ' + ', '.join(references))
