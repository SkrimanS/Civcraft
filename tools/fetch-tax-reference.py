from pathlib import Path
from urllib.request import urlopen
import json
root=Path(__file__).resolve().parent/'legacy-reference'
tree=json.loads((root/'original-tree.json').read_text(encoding='utf-8'))['tree']
names={'Resident.java','EconObject.java','TownChunk.java','CivSettings.java','TownSetCommand.java','CivSetCommand.java','ResidentCommand.java'}
for item in tree:
    name=Path(item['path']).name
    if name not in names or '/src/' not in item['path']:continue
    target=root/name
    if not target.exists():
        with urlopen('https://raw.githubusercontent.com/ataranlen/civcraft/1.8-Beta-6/'+item['path'],timeout=30) as response:target.write_bytes(response.read())
    print(item['path'])
