from pathlib import Path
from urllib.request import urlopen
import json
root=Path('tools/legacy-reference')
index=root/'original-tree.json'
if not index.exists():
    with urlopen('https://api.github.com/repos/ataranlen/civcraft/git/trees/1.8-Beta-6?recursive=1',timeout=30) as response:index.write_bytes(response.read())
tree=json.loads(index.read_text(encoding='utf-8'))['tree']
names={'FoundCivSync.java','FoundCamp.java','AttributeStatic.java','AttributeRate.java','Treasury.java'}
for item in tree:
    name=Path(item['path']).name
    if name not in names or '/src/' not in item['path']:continue
    target=root/name
    if not target.exists():
        with urlopen('https://raw.githubusercontent.com/ataranlen/civcraft/1.8-Beta-6/'+item['path'],timeout=30) as response:target.write_bytes(response.read())
    print(item['path'])
