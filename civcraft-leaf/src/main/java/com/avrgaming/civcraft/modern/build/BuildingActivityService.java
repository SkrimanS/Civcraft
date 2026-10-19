package com.avrgaming.civcraft.modern.build;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.town.TownCatalog;
import java.io.File;
import java.sql.*;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;

public final class BuildingActivityService {
    private record Rule(String name,int limit,boolean tile) {}
    private record Building(long id,long town,String type,Long registered) {}
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final TownCatalog towns;
    private final Map<String,Rule> rules=new LinkedHashMap<>();
    public BuildingActivityService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException{
        this.plugin=plugin;this.storage=storage;towns=new TownCatalog(new File(plugin.getDataFolder(),"civcraft/data/town.yml"));
        for(var row:YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"civcraft/data/structures.yml")).getMapList("structures"))rules.put(Objects.toString(row.get("id")),new Rule(Objects.toString(row.get("displayName")),row.get("limit") instanceof Number n?n.intValue():0,Boolean.TRUE.equals(row.get("tile_improvement"))));
        storage.transaction(db->{BuildingActivityStore.install(db);reconcile(db,System.currentTimeMillis());return null;});
    }
    private int maximum(Connection db,long town)throws SQLException{
        int level;try(var s=db.prepareStatement("SELECT level FROM towns WHERE id=?")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing activity town");level=r.getInt(1);}}
        int maximum=towns.level(level).improvements();
        try(var s=db.prepareStatement("SELECT 1 FROM active_structure_builds b JOIN towns source ON source.id=b.town_id JOIN towns target ON target.civ_id=source.civ_id WHERE target.id=? AND b.structure_id='w_mother_tree' LIMIT 1")){s.setLong(1,town);try(var r=s.executeQuery()){if(r.next())maximum=Math.multiplyExact(maximum,2);}}
        return maximum;
    }
    private boolean counted(String status){return !Set.of("DEMOLISHED","CANCELLED","DEMOLISH_PENDING","ROLLBACK_PENDING").contains(status);}
    private void reconcile(Connection db,long now)throws SQLException{
        var buildings=new ArrayList<Building>();
        try(var s=db.createStatement();var r=s.executeQuery("SELECT b.id,b.town_id,b.structure_id,b.status,a.town_id FROM structure_builds b LEFT JOIN structure_activity a ON a.build_id=b.id WHERE b.town_id IS NOT NULL AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"structure_builds","b.id")+" AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"towns","b.town_id")+" ORDER BY b.id")){while(r.next())if(counted(r.getString(4)))buildings.add(new Building(r.getLong(1),r.getLong(2),r.getString(3),r.getObject(5)==null?null:r.getLong(5)));}
        var counts=new HashMap<String,Integer>();var tiles=new HashMap<Long,Integer>();
        for(var b:buildings)if(b.registered()!=null && b.registered()==b.town())add(counts,tiles,b);
        for(var b:buildings)if(b.registered()==null || b.registered()!=b.town()){
            add(counts,tiles,b);var rule=rules.get(b.type());boolean enabled=rule==null || (rule.tile()?tiles.getOrDefault(b.town(),0)<=maximum(db,b.town()):rule.limit()==0 || counts.getOrDefault(b.town()+":"+b.type(),0)<=rule.limit());
            BuildingActivityStore.set(db,b.id(),b.town(),enabled,enabled?"":"Превышен лимит построек города",now);
        }
    }
    private void add(Map<String,Integer> counts,Map<Long,Integer> tiles,Building b){counts.merge(b.town()+":"+b.type(),1,Integer::sum);var rule=rules.get(b.type());if(rule!=null && rule.tile())tiles.merge(b.town(),1,Integer::sum);}
    public void tick(){try{storage.transaction(db->{reconcile(db,System.currentTimeMillis());return null;});}catch(SQLException|RuntimeException failure){plugin.getLogger().warning("Unable to reconcile building activity: "+failure.getMessage());}}
    public void requireCapacity(Connection db,long town,String type,boolean adding)throws SQLException{
        var rule=rules.get(type);if(rule==null)return;int count=0;
        try(var s=db.prepareStatement("SELECT structure_id,status FROM structure_builds WHERE town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){while(r.next())if(counted(r.getString(2))){var other=rules.get(r.getString(1));if(rule.tile()?other!=null && other.tile():type.equals(r.getString(1)))count++;}}}
        int limit=rule.tile()?maximum(db,town):rule.limit();if((rule.tile() || limit>0) && (long)count+(adding?1:0)>limit)throw new IllegalArgumentException(rule.tile()?"Достигнут лимит улучшений территории города: "+limit:"Достигнут лимит построек: "+rule.name());
    }
    public void enable(Player player,String reference)throws SQLException{
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();if(resident.townId()==null || resident.civId()==null)throw new IllegalArgumentException("Вы не состоите в городе цивилизации.");
        storage.transaction(db->{
            try(var s=db.prepareStatement("SELECT 1 FROM civilizations WHERE id=? AND leader_uuid=?")){s.setLong(1,resident.civId());s.setString(2,player.getUniqueId().toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Включать постройки может лидер цивилизации.");}}
            long now=System.currentTimeMillis();try(var s=db.prepareStatement("SELECT 1 FROM war_sessions WHERE state='ACTIVE' AND start_at<=? AND end_at>?")){s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Во время боевого периода включать постройки нельзя.");}}
            long build;String type;
            String[] coord=reference.split(",",-1);String query=coord.length==4?"world=? AND x=? AND y=? AND z=?":"id=?";
            try(var s=db.prepareStatement("SELECT id,structure_id FROM structure_builds WHERE town_id=? AND status='COMPLETE' AND "+query)){s.setLong(1,resident.townId());try{if(coord.length==4){s.setString(2,coord[0]);s.setInt(3,Integer.parseInt(coord[1]));s.setInt(4,Integer.parseInt(coord[2]));s.setInt(5,Integer.parseInt(coord[3]));}else s.setLong(2,Long.parseLong(reference));}catch(NumberFormatException invalid){throw new IllegalArgumentException("Укажите ID постройки или мир,x,y,z.");}try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Завершённая постройка вашего города не найдена.");build=r.getLong(1);type=r.getString(2);}}
            requireCapacity(db,resident.townId(),type,false);if(!BuildingActivityStore.set(db,build,resident.townId(),true,"",now))throw new IllegalArgumentException("Постройка ещё сохраняет операцию. Повторите позже.");return null;
        });player.sendMessage(Component.text("Постройка включена."));
    }
    public void info(Player player)throws SQLException{
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();if(resident.townId()==null)throw new IllegalArgumentException("Вы не состоите в городе.");
        var lines=storage.query(db->{var result=new ArrayList<String>();try(var s=db.prepareStatement("SELECT b.id,b.structure_id,b.status,b.world,b.x,b.y,b.z,COALESCE(a.enabled,1),COALESCE(a.reason,'') FROM structure_builds b LEFT JOIN structure_activity a ON a.build_id=b.id WHERE b.town_id=? AND b.status NOT IN ('DEMOLISHED','CANCELLED') ORDER BY b.id")){s.setLong(1,resident.townId());try(var r=s.executeQuery()){while(r.next()){var rule=rules.get(r.getString(2));result.add("#"+r.getLong(1)+" "+(rule==null?r.getString(2):rule.name())+" — "+(r.getBoolean(8)?"разрешено":"отключено: "+r.getString(9))+" ("+status(r.getString(3))+"; "+r.getString(4)+","+r.getInt(5)+","+r.getInt(6)+","+r.getInt(7)+")");}}}return result;});for(String line:lines)player.sendMessage(Component.text(line));
    }
    private String status(String status){return BuildStatusText.name(status);}
}
