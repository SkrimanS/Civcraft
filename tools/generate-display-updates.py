"""Build a conditional display-only migration from the last pre-L01 resource revision."""
from pathlib import Path
import subprocess,yaml

baseline='7d2f35cc07853154c49b5b06311737e578b05bcf'
root=Path('civcraft-leaf/src/main/resources')
manifest=root/'lang/display-updates.yml'
updates=[]
class NoAliases(yaml.SafeDumper):
    def ignore_aliases(self,value):return True
def same_record(left,right,match):
    return isinstance(left,dict) and isinstance(right,dict) and all(str(left.get(key))==str(value) and str(right.get(key))==str(value) for key,value in match.items())
def selector(before,after,index):
    old,new=before[index],after[index]
    if isinstance(old,dict) and isinstance(new,dict):
        for keys in (('id',),('level',),('store_id','horse_id','item_id'),('effect','amp'),('name',)):
            if not all(key in old for key in keys):continue
            match={key:old[key] for key in keys}
            if same_record(old,new,match) and sum(same_record(row,row,match) for row in before)==1 and sum(same_record(row,row,match) for row in after)==1:return {'match':match}
    return index
def visit(file,before,after,path=(),raw_path=()):
    if before==after:return
    if isinstance(before,dict) and isinstance(after,dict):
        assert before.keys()==after.keys(),(file,raw_path,'keys changed')
        for key in before:visit(file,before[key],after[key],path+(str(key),),raw_path+(str(key),))
    elif isinstance(before,list) and isinstance(after,list):
        assert len(before)==len(after),(file,raw_path,'list size changed')
        for index in range(len(before)):visit(file,before[index],after[index],path+(selector(before,after,index),),raw_path+(index,))
    else:
        assert isinstance(before,str) and isinstance(after,str),(file,raw_path,'non-text changed')
        allowed=file.startswith('lang/') or bool({'name','displayName','display-name','description','message','title','lore'}.intersection(raw_path[-2:]))
        assert allowed,(file,raw_path,'not a display field')
        if raw_path[-1]=='name':assert not {'components','actions','requirements','success','failure'}.intersection(raw_path),(file,raw_path,'component identifier changed')
        updates.append({'file':file,'path':list(path),'before':before,'after':after})
for file in sorted(root.rglob('*.yml')):
    relative=file.relative_to(root).as_posix()
    if file==manifest or relative=='plugin.yml':continue
    source=subprocess.run(['git','show',baseline+':'+file.as_posix()],capture_output=True)
    if source.returncode:continue
    before=yaml.safe_load(source.stdout.decode('utf-8-sig'))
    after=yaml.safe_load(file.read_text(encoding='utf-8-sig'))
    visit(relative,before,after)
manifest.parent.mkdir(parents=True,exist_ok=True)
manifest.write_text('# Conditional Russian display updates. Gameplay parameters are excluded.\n'+yaml.dump({'version':'ru-1','updates':updates},Dumper=NoAliases,allow_unicode=True,sort_keys=False,width=120),encoding='utf-8',newline='\n')
print('Prepared',len(updates),'conditional text updates across',len({row['file'] for row in updates}),'files.')
