from pathlib import Path
from urllib.request import urlopen
base='https://raw.githubusercontent.com/ataranlen/civcraft/1.8-Beta-6/civcraft/src/com/avrgaming/civcraft/'
for name in ['object/Civilization.java','command/civ/CivGovCommand.java','threading/timers/ChangeGovernmentTimer.java']:
    target=Path('tools/legacy-reference')/Path(name).name
    if not target.exists():
        with urlopen(base+name,timeout=20) as response: target.write_bytes(response.read())
