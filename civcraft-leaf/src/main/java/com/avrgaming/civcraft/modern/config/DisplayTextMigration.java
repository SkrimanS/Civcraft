package com.avrgaming.civcraft.modern.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Replace only known former display defaults; custom text and gameplay fields are not targets. */
public final class DisplayTextMigration {
    private DisplayTextMigration() {}
    private record Edit(List<?> path,String before,String after){}

    public static void apply(JavaPlugin plugin)throws IOException,InvalidConfigurationException {
        var manifest=new YamlConfiguration();
        try(var resource=plugin.getResource("lang/display-updates.yml")) {
            if(resource==null)throw new IOException("Не найден список обновлений русского текста.");
            manifest.load(new InputStreamReader(resource,StandardCharsets.UTF_8));
        }
        String version=manifest.getString("version","");
        if(!version.matches("[a-zA-Z0-9_-]+"))throw new IOException("Недопустимая версия обновления русского текста.");
        Path root=plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path realRoot=root.toRealPath();
        var groups=new LinkedHashMap<Path,List<Edit>>();
        for(var row:manifest.getMapList("updates")) {
            if(!(row.get("file") instanceof String file) || !(row.get("path") instanceof List<?> path) || path.isEmpty()
                    || !(row.get("before") instanceof String before) || !(row.get("after") instanceof String after))throw new IOException("Неверная запись обновления русского текста.");
            Path relative=Path.of(file),target=root.resolve(relative).normalize();
            if(relative.isAbsolute() || !target.startsWith(root) || !file.endsWith(".yml"))throw new IOException("Обновление текста выходит за каталог плагина: "+file);
            groups.computeIfAbsent(target,ignored->new ArrayList<>()).add(new Edit(List.copyOf(path),before,after));
        }
        int files=0,total=0;
        for(var group:groups.entrySet()) {
            Path target=group.getKey();
            if(!Files.exists(target))continue; // New files are copied from already translated resources later.
            if(!Files.isRegularFile(target) || !target.toRealPath().startsWith(realRoot))throw new IOException("Файл перевода расположен вне каталога плагина: "+target);
            if(Files.isSymbolicLink(target)){plugin.getLogger().warning("Обновление текста пропущено для символической ссылки: "+root.relativize(target));continue;}
            String original=Files.readString(target,StandardCharsets.UTF_8);
            var config=new YamlConfiguration();config.options().parseComments(true);config.loadFromString(original);
            int changed=0;for(var edit:group.getValue())if(apply(config,edit))changed++;
            if(changed==0)continue;
            String replacement=config.saveToString();
            Path backup=root.resolve("display-text-backups").resolve(version).resolve(root.relativize(target)).normalize();
            if(!backup.startsWith(root))throw new IOException("Недопустимый путь копии текста.");
            Files.createDirectories(backup.getParent());
            if(!backup.getParent().toRealPath().startsWith(realRoot))throw new IOException("Каталог копий расположен вне каталога плагина.");
            Path temporary=Files.createTempFile(target.getParent(),"ru-display-",".tmp");
            try {
                Files.writeString(temporary,replacement,StandardCharsets.UTF_8);
                if(!Files.readString(target,StandardCharsets.UTF_8).equals(original))throw new IOException("Файл изменён во время обновления текста: "+target);
                try{Files.writeString(backup,original,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
                catch(FileAlreadyExistsException existing){if(!Files.isRegularFile(backup) || Files.isSymbolicLink(backup) || !backup.toRealPath().startsWith(realRoot))throw new IOException("Недопустимая копия исходного текста: "+backup,existing);}
                try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(temporary);}
            files++;total+=changed;
        }
        if(files>0)plugin.getLogger().info("Обновлён русский текст: "+total+" полей в "+files+" файлах. Исходные копии: display-text-backups/"+version+".");
    }

    private static boolean apply(ConfigurationSection root,Edit edit) {
        Object current=root;
        for(int i=0;i<edit.path().size()-1;i++){current=child(current,edit.path().get(i));if(current==null)return false;}
        Object step=edit.path().getLast();
        if(current instanceof ConfigurationSection section && step instanceof String key) {
            if(!edit.before().equals(section.get(key)))return false;section.set(key,edit.after());return true;
        }
        if(current instanceof Map<?,?> map && step instanceof String key) {
            if(!edit.before().equals(map.get(key)))return false;
            put(map,key,edit.after());return true;
        }
        if(current instanceof List<?> list && step instanceof Number index) {
            int target=index.intValue();
            if(target>=0 && target<list.size()) {
                if(edit.after().equals(list.get(target)))return false;
                if(edit.before().equals(list.get(target))){set(list,target,edit.after());return true;}
            }
            // A user may have reordered lore/description lines. Change an exact unique old line only.
            int found=-1;for(int i=0;i<list.size();i++)if(edit.before().equals(list.get(i))){if(found>=0)return false;found=i;}
            if(found>=0){set(list,found,edit.after());return true;}
        }
        return false;
    }
    private static Object child(Object parent,Object step) {
        if(step instanceof String key) {
            if(parent instanceof ConfigurationSection section)return section.get(key);
            if(parent instanceof Map<?,?> map)return map.get(key);
        }
        if(parent instanceof List<?> list) {
            if(step instanceof Number number){int index=number.intValue();return index>=0 && index<list.size()?list.get(index):null;}
            if(step instanceof Map<?,?> selector && selector.get("match") instanceof Map<?,?> match && !match.isEmpty()) {
                Object found=null;
                for(var row:list)if(matches(row,match)){if(found!=null)return null;found=row;}
                return found;
            }
        }
        return null;
    }
    private static boolean matches(Object value,Map<?,?> expected) {
        if(!(value instanceof Map<?,?>) && !(value instanceof ConfigurationSection))return false;
        for(var entry:expected.entrySet()){Object actual=child(value,entry.getKey().toString());if(actual==null || !actual.toString().equals(Objects.toString(entry.getValue())))return false;}
        return true;
    }
    @SuppressWarnings("unchecked") private static void put(Map<?,?> map,String key,String value){((Map<Object,Object>)map).put(key,value);}
    @SuppressWarnings("unchecked") private static void set(List<?> list,int index,String value){((List<Object>)list).set(index,value);}
}
