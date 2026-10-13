from pathlib import Path
from urllib.request import urlopen
base='https://raw.githubusercontent.com/ataranlen/civcraft/1.8-Beta-6/civcraft/src/com/avrgaming/civcraft/items/components/'
out=Path('tools/legacy-reference')
for name in ['Attack','Defense','DurabilityOnDeath','LeatherColor','AllowBlockPlace','RangedAttack','RepairCost','NoVanillaDurability']:
    target=out/(name+'.java')
    if not target.exists():
        with urlopen(base+name+'.java',timeout=20) as response: target.write_bytes(response.read())
    print(name)
