package com.avrgaming.civcraft.modern.government;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public final class GovernmentService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final GovernmentDefinitions definitions;
    public GovernmentService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        definitions=new GovernmentDefinitions(new File(plugin.getDataFolder(),"civcraft/data/governments.yml"));
        storage.query(db->{GovernmentStore.install(db);return null;});
        storage.setGovernments(definitions);
        // Repair persisted timing before the world's queued builds are recovered.
        var active=storage.query(db->{
            List<Long> ids=new ArrayList<>();
            try(var result=db.createStatement().executeQuery("SELECT DISTINCT t.civ_id FROM structure_builds sb JOIN towns t ON t.id=sb.town_id WHERE sb.status='QUEUED'")){while(result.next())ids.add(result.getLong(1));}
            return ids;
        });
        for(long id:active)storage.transaction(db->retimeData(db,id));
    }
    public String name(String stored){return definitions.require(stored).name();}
    public List<String> ids(){return definitions.list().stream().map(GovernmentDefinition::id).filter(id->!id.equals("gov_anarchy")).toList();}
    public void command(Player player,String[] args)throws SQLException {
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        if(resident.civId()==null)throw new IllegalArgumentException("Вы не состоите в цивилизации.");
        var civ=storage.findCiv(resident.civId()).orElseThrow();
        String action=args.length>2?args[2].toLowerCase(Locale.ROOT):"info";
        if(action.equals("list")) {
            var unlocked=storage.researchedTechs(civ.id());
            for(var definition:definitions.list()) {
                if(definition.id().equals("gov_anarchy"))continue;
                player.sendMessage(Component.text(definition.id()+" — "+definition.name()
                        +(definition.technology().isBlank() || unlocked.contains(definition.technology())?" (доступно)":" (требуется "+definition.technology()+")")));
            }
        } else if(action.equals("change")) {
            if(args.length<4)throw new IllegalArgumentException("Использование: /civ gov change <правительство>");
            var target=definitions.require(String.join(" ",Arrays.copyOfRange(args,3,args.length)));
            var changes=storage.transaction(db->{
                GovernmentStore.begin(db,civ.id(),player.getUniqueId(),target,System.currentTimeMillis());
                return retimeData(db,civ.id());
            });
            applyTiming(changes);
            plugin.getServer().broadcast(Component.text("Цивилизация "+civ.name()+" вступила в анархию. Новое правительство: "+target.name()+"."));
        } else if(action.equals("info")) {
            var current=definitions.require(civ.government());
            player.sendMessage(Component.text("Правительство: "+current.name()+". Молотки ×"+current.hammers()+", наука ×"+current.beakers()+", содержание ×"+current.upkeep()));
            var transition=storage.query(db->GovernmentStore.transition(db,civ.id()));
            if(transition!=null) {
                long remaining=Math.max(0,transition.startedAt()+duration(civ.id())-System.currentTimeMillis());
                player.sendMessage(Component.text("После анархии: "+name(transition.target())+". Осталось минут: "+((remaining+59999)/60000)));
            }
        } else throw new IllegalArgumentException("Команды: /civ gov info, list, change <правительство>");
    }
    private long duration(long civId)throws SQLException {
        return storage.query(db->{
            boolean notre;
            try(var query=db.prepareStatement("SELECT 1 FROM structure_builds sb JOIN towns t ON t.id=sb.town_id WHERE t.civ_id=? AND sb.structure_id='w_notre_dame' AND sb.status='COMPLETE' LIMIT 1")) {
                query.setLong(1,civId);try(var result=query.executeQuery()){notre=result.next();}
            }
            double memberHours=0;
            try(var query=db.prepareStatement("""
                    SELECT t.id,COUNT(r.uuid),EXISTS(SELECT 1 FROM structure_builds sb
                        WHERE sb.town_id=t.id AND sb.structure_id='s_broadcast_tower' AND sb.status='COMPLETE')
                    FROM towns t LEFT JOIN residents r ON r.town_id=t.id WHERE t.civ_id=? GROUP BY t.id
                    """)) {
                query.setLong(1,civId);try(var result=query.executeQuery()) {
                    while(result.next())memberHours+=result.getInt(2)*Math.max(0,1-(result.getBoolean(3)?definitions.broadcastReduction():0)-(notre?definitions.notreReduction():0));
                }
            }
            return GovernmentStore.durationMillis(memberHours,notre?definitions.notreMaximumHours():definitions.maximumAnarchyHours());
        });
    }
    public void tick() {
        try {
            List<Long> ids=storage.query(db->{
                List<Long> list=new ArrayList<>();
                try(var result=db.createStatement().executeQuery("SELECT civ_id FROM government_transitions")){while(result.next())list.add(result.getLong(1));}
                return list;
            });
            for(long id:ids) {
                long duration=duration(id);
                var transition=storage.query(db->GovernmentStore.transition(db,id));
                var changes=transition==null?null:storage.transaction(db->{
                    return GovernmentStore.finish(db,transition,System.currentTimeMillis(),duration)?retimeData(db,id):null;
                });
                if(changes!=null) {
                    applyTiming(changes);
                    var civ=storage.findCiv(id).orElseThrow();
                    plugin.getServer().broadcast(Component.text("Анархия в цивилизации "+civ.name()+" завершилась. Правительство: "+name(civ.government())+"."));
                }
            }
        } catch(SQLException exception){plugin.getLogger().warning("Government tick failed: "+exception.getMessage());}
    }
    private Map<Long,Long> retimeData(Connection db,long civId)throws SQLException {
        // Changes are persisted before changing the in-memory placement speed.
            Map<Long,Long> changes=new LinkedHashMap<>();
            List<long[]> builds=new ArrayList<>();
            try(var query=db.prepareStatement("SELECT sb.id,sb.town_id FROM structure_builds sb JOIN towns t ON t.id=sb.town_id WHERE t.civ_id=? AND sb.status='QUEUED'")) {
                query.setLong(1,civId);try(var result=query.executeQuery()){while(result.next())builds.add(new long[]{result.getLong(1),result.getLong(2)});}
            }
            long now=System.currentTimeMillis();
            for(var build:builds) {
                var town=storage.findTown(build[1]).orElseThrow();
                try(var query=db.prepareStatement("SELECT total_hammers,started_at,finish_at FROM structure_builds WHERE id=?")) {
                    query.setLong(1,build[0]);try(var result=query.executeQuery()) {
                        if(!result.next())continue;
                        if(town.hammersPerHour()<=0)throw new SQLException("Government change would stop construction without production");
                        long previousStart=result.getLong(2),previousFinish=result.getLong(3);
                        double progress=previousFinish<=previousStart?1:Math.max(0,Math.min(1,(now-previousStart)/(double)(previousFinish-previousStart)));
                        long duration=(long)Math.ceil(result.getDouble(1)/town.hammersPerHour()*3_600_000);
                        long start=now-(long)(progress*duration);
                        try(var update=db.prepareStatement("UPDATE structure_builds SET started_at=?,finish_at=?,hammers_per_hour=? WHERE id=?")) {
                            update.setLong(1,start);update.setLong(2,start+duration);update.setDouble(3,town.hammersPerHour());update.setLong(4,build[0]);update.executeUpdate();
                        }
                        changes.put(build[0],duration);
                    }
                }
            }
            return changes;
    }
    private void applyTiming(Map<Long,Long> changes) {
        changes.forEach((id,duration)->plugin.legacyStructures().retimeBuild(id,duration));
    }
}
