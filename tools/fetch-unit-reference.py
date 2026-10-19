"""Cache the original unit inventory handlers for the A01 source comparison."""
from pathlib import Path
from urllib.request import urlopen
import json

root=Path('tools/legacy-reference')
tree=json.loads((root/'original-tree.json').read_text(encoding='utf-8'))['tree']
names={'CustomItemManager.java','DelayMoveInventoryItem.java','PlayerListener.java','InteractiveTownName.java','InteractiveConfirmTownCreation.java','FoundTownSync.java','TownNewRequest.java','CivLeaderQuestionTask.java'}
for entry in tree:
    path=entry['path']
    if not path.endswith('.java') or '/src/' not in path:continue
    name=Path(path).name
    if '/items/units/' not in path and name not in names:continue
    target=root/name
    if not target.exists():
        with urlopen('https://raw.githubusercontent.com/ataranlen/civcraft/1.8-Beta-6/'+path,timeout=30) as response:
            target.write_bytes(response.read())
    print(path)
