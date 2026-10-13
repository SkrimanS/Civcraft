from pathlib import Path
from urllib.request import urlopen
import json
root=Path(__file__).resolve().parent/'legacy-reference'
tree=json.loads((root/'original-tree.json').read_text(encoding='utf-8'))['tree']
names={'CultureChunk.java','ConfigCultureBiomeInfo.java','ConfigCultureLevel.java','CultureProcess.java','CultureTask.java','CultureTimer.java','TownInfoCommand.java','TownSurveyCommand.java','Survey.java','CivGlobal.java','BiomeCache.java','SyncTradeTimer.java','EffectEventTimer.java','Temple.java'}
for item in tree:
    name=Path(item['path']).name
    if '/src/' not in item['path'] or (name not in names and 'Survey' not in name and 'Culture' not in name and 'Hourly' not in name):continue
    target=root/name
    if not target.exists():
        with urlopen('https://raw.githubusercontent.com/ataranlen/civcraft/1.8-Beta-6/'+item['path'],timeout=30) as response:target.write_bytes(response.read())
    print(item['path'])
