"""Inventory ordinary sign text, without changing templates or command markers."""
from pathlib import Path
import collections,json,re,sys
root=Path('civcraft-leaf/src/main/resources/legacy-templates')
signs=collections.defaultdict(list)
for file in root.rglob('*.def'):
    with file.open(encoding='utf-8-sig') as source:
        for number,line in enumerate(source,1):
            fields=line.rstrip('\r\n').split(',',2)
            if len(fields)!=3 or fields[1].split(':',1)[0] not in ('63','68'):continue
            text=(fields[2].split(',')+['']*4)[:4]
            if text[0].strip().startswith('/') or not any(re.search('[A-Za-z]{2,}',row) for row in text):continue
            signs[tuple(text)].append({'file':str(file.relative_to(root)),'line':number,'position':fields[0]})
rows=[{'text':list(text),'occurrences':locations} for text,locations in sorted(signs.items())]
Path('../tools/template-sign-inventory.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
print('Unique literal sign texts:',len(rows),'placements:',sum(len(row['occurrences']) for row in rows))
for index,row in enumerate(rows[:95]):print(index,json.dumps(row['text'],ensure_ascii=False),len(row['occurrences']),row['occurrences'][0]['file'])
if '--check-catalog' in sys.argv:
    source=Path('civcraft-leaf/src/main/java/com/avrgaming/civcraft/modern/build/LegacySignText.java').read_text(encoding='utf-8')
    quoted=re.compile(r'"(?:\\.|[^"\\])*"')
    normalize=lambda lines:tuple(line.strip().lstrip('\\').strip().lower() for line in lines)
    translated={}
    for match in re.finditer(r'add\(result,List\.of\((.*?)\),List\.of\((.*?)\)\);',source):
        before,after=(list(map(json.loads,quoted.findall(part))) for part in match.groups())
        if '+first' in match.group(1):
            for first in (1,3,5,7):translated[normalize(['Trade Good','Slot:'+str(first),'Slot:'+str(first+1),''])]=['Торговый товар','Ячейка: '+str(first),'Ячейка: '+str(first+1),'']
        else:
            assert len(before)==len(after)==4
            assert normalize(before) not in translated
            translated[normalize(before)]=after
    missing=[row for row in rows if normalize(row['text']) not in translated]
    assert not missing,missing
    for text in translated.values():assert max(map(len,text))<=15,text
    print('Catalog covers',len(rows),'unique English signs and',sum(len(row['occurrences']) for row in rows),'template placements; translated lines fit the legacy 15-character limit.')
