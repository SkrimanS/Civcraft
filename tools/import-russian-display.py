"""Reuse translated display fields without importing old gameplay or storage settings."""
from pathlib import Path
import json
import yaml

source = Path('../../server/plugins/CivCraft/data')
targets = [Path('civcraft-leaf/src/main/resources/civcraft/data'), Path('../server/plugins/CivCraft/civcraft/data')]
fields = {'name', 'displayName', 'category', 'lore'}
count = 0
for filename in ('materials.yml', 'structures.yml', 'wonders.yml', 'techs.yml', 'governments.yml', 'goods.yml', 'units.yml','market.yml'):
    old = yaml.safe_load((source / filename).read_text(encoding='utf-8-sig'))
    translated = {}
    def collect(value):
        if isinstance(value, dict):
            if 'id' in value:
                translated[str(value['id'])] = {k: v for k, v in value.items() if k in fields}
            for child in value.values(): collect(child)
        elif isinstance(value, list):
            for child in value: collect(child)
    collect(old)
    for folder in targets:
        path = folder / filename
        text = path.read_text(encoding='utf-8')
        edits = []
        def visit(node):
            if isinstance(node, yaml.MappingNode):
                ident = next((v.value for k, v in node.value if k.value == 'id' and isinstance(v, yaml.ScalarNode)), None)
                names = translated.get(ident, {})
                for key, value in node.value:
                    if key.value in names:
                        wanted = names[key.value]
                        if isinstance(value, yaml.ScalarNode) and isinstance(wanted, str) and value.value != wanted:
                            edits.append((value.start_mark.index, value.end_mark.index, json.dumps(wanted, ensure_ascii=False)))
                        elif isinstance(value, yaml.SequenceNode) and isinstance(wanted, list) and len(value.value) == len(wanted):
                            for item, replacement in zip(value.value, wanted):
                                if isinstance(item, yaml.ScalarNode) and isinstance(replacement, str) and item.value != replacement:
                                    edits.append((item.start_mark.index, item.end_mark.index, json.dumps(replacement, ensure_ascii=False)))
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
