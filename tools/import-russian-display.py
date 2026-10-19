"""Reuse translated display fields without importing old gameplay or storage settings."""
from pathlib import Path
import json
import yaml

source = Path('../../server/plugins/CivCraft/data')
targets = [Path('civcraft-leaf/src/main/resources/civcraft/data'), Path('../server/plugins/CivCraft/civcraft/data')]
fields = {'name', 'displayName', 'category', 'lore','title'}
count = 0
for filename in ('materials.yml', 'structures.yml', 'wonders.yml', 'techs.yml', 'governments.yml', 'goods.yml', 'units.yml','market.yml','town.yml'):
    old = yaml.safe_load((source / filename).read_text(encoding='utf-8-sig'))
    translated = {}
    def collect(value):
        if isinstance(value, dict):
            if 'id' in value:
                translated.setdefault(str(value['id']), {}).update({k: v for k, v in value.items() if k in fields})
            elif 'level' in value and 'title' in value:
                translated['town-level:'+str(value['level'])]={'title':value['title']}
            for child in value.values(): collect(child)
        elif isinstance(value, list):
            for child in value: collect(child)
    collect(old)
    if filename=='town.yml':
        translated['town-level:1']={'title':'Поселение'}
        for level in range(2,10):
            name=translated.get('upgrade_town_level_'+str(level),{}).get('name')
            if name:translated['town-level:'+str(level)]={'title':name}
        for level,roman in enumerate(['I','II','III','IV'],1):
            translated.setdefault('upgrade_bank_interest_level_'+str(level),{})['name']='Проценты банка '+roman
    for folder in targets:
        path = folder / filename
        text = path.read_text(encoding='utf-8')
        edits = []
        def scalar_end(node):
            end=node.end_mark.index
            while end<len(text) and text[end] in ' \t':end+=1
            return end if end==len(text) or text[end] in '\r\n' else node.end_mark.index
        def visit(node):
            if isinstance(node, yaml.MappingNode):
                ident = next((v.value for k, v in node.value if k.value == 'id' and isinstance(v, yaml.ScalarNode)), None)
                if ident is None and any(k.value=='title' for k,v in node.value):
                    level=next((v.value for k,v in node.value if k.value=='level' and isinstance(v,yaml.ScalarNode)),None)
                    if level is not None:ident='town-level:'+level
                names = translated.get(ident, {})
                for key, value in node.value:
                    if key.value in names:
                        wanted = names[key.value]
                        if isinstance(value, yaml.ScalarNode) and isinstance(wanted, str) and value.value != wanted:
                            edits.append((value.start_mark.index, scalar_end(value), json.dumps(wanted, ensure_ascii=False)))
                        elif isinstance(value, yaml.SequenceNode) and isinstance(wanted, list) and len(value.value) == len(wanted):
                            for item, replacement in zip(value.value, wanted):
                                if isinstance(item, yaml.ScalarNode) and isinstance(replacement, str) and item.value != replacement:
                                    edits.append((item.start_mark.index, scalar_end(item), json.dumps(replacement, ensure_ascii=False)))
                    visit(value)
            elif isinstance(node, yaml.SequenceNode):
                for child in node.value: visit(child)
        visit(yaml.compose(text))
        for start, end, replacement in sorted(edits, reverse=True): text = text[:start] + replacement + text[end:]
        yaml.safe_load(text)
        path.write_text(text, encoding='utf-8')
        count += len(edits)
        print(f'{path}: {len(edits)} display fields')
print(f'Translated {count} display fields; gameplay parameters retained.')
