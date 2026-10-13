from pathlib import Path
from urllib.request import urlopen
base='https://raw.githubusercontent.com/ataranlen/civcraft/1.8-Beta-6/civcraft/src/com/avrgaming/civcraft/'
for name in ['listener/PlayerListener.java','listener/CustomItemManager.java','lorestorage/LoreCraftableMaterial.java','items/units/Unit.java','items/components/Soulbound.java','loreenhancements/LoreEnhancementSoulBound.java']:
    target=Path('tools/legacy-reference')/Path(name).name
    if not target.exists():
        with urlopen(base+name,timeout=20) as response: target.write_bytes(response.read())
