package com.avrgaming.civcraft.modern.espionage;

import java.sql.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.configuration.file.YamlConfiguration;
import com.avrgaming.civcraft.modern.government.GovernmentStore;
import com.avrgaming.civcraft.modern.town.GranaryPoisonStore;
import com.avrgaming.civcraft.modern.war.BuildingDamageStore;

/** Database effects join the mission result and inventory intent in a single transaction. */
public final class MissionEffects {
    private final int poisonMin,poisonMax;
    private final double famine;
    public MissionEffects(java.io.File file) {
        var yaml=YamlConfiguration.loadConfiguration(file);poisonMin=yaml.getInt("espionage.poison_granary_min_ticks",3);poisonMax=yaml.getInt("espionage.poison_granary_max_ticks",50);famine=yaml.getDouble("espionage.poison_granary_famine_chance",.05);
        if(poisonMin<1 || poisonMax<=poisonMin || !Double.isFinite(famine) || famine<0 || famine>1)throw new IllegalArgumentException("Неверные настройки шпионского отравления");
    }
    public String apply(Connection db,long source,MissionTargets.Target target,long now)throws SQLException {
        return apply(db,source,target,now,null);
    }
    public String apply(Connection db,long source,MissionTargets.Target target,long now,com.avrgaming.civcraft.modern.build.BuildQueue.DestructionSnapshot snapshot)throws SQLException {
        long town=target.town().id();
        switch(target.mission().id()) {
            case "spy_investigate_town" -> {return "Город разведан.";}
            case "spy_steal_treasury" -> {
                long amount;try(var s=db.prepareStatement("SELECT coins/5 FROM towns WHERE id=?")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing espionage target");amount=r.getLong(1);}}
                try(var s=db.prepareStatement("UPDATE towns SET coins=coins-? WHERE id=? AND coins>=?")){s.setLong(1,amount);s.setLong(2,town);s.setLong(3,amount);if(s.executeUpdate()!=1)throw new SQLException("Treasury changed");}
                try(var s=db.prepareStatement("UPDATE towns SET coins=coins+? WHERE id=?")){s.setLong(1,amount);s.setLong(2,source);if(s.executeUpdate()!=1)throw new SQLException("Source town disappeared");}return "В казну вашего города переведено "+amount+" монет.";
            }
            case "spy_poison_granary" -> {
                int ticks=ThreadLocalRandom.current().nextInt(poisonMin,poisonMax);GranaryPoisonStore.apply(db,town,ticks);
                boolean starve=ThreadLocalRandom.current().nextInt(100)<(int)(famine*100);
                if(starve)try(var s=db.prepareStatement("UPDATE building_consumption SET level=level-1,count=0 WHERE level>1 AND build_id IN (SELECT id FROM structure_builds WHERE town_id=? AND structure_id='s_cottage')")){s.setLong(1,town);s.executeUpdate();}
                return "Амбар отравлен на "+ticks+" циклов питания."+(starve?" Голод понизил уровень коттеджей.":"");
            }
            case "spy_subvert_government" -> {
                GovernmentStore.install(db);String government;try(var s=db.prepareStatement("SELECT government FROM civilizations WHERE id=?")){s.setLong(1,target.town().civ());try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Civilization disappeared");government=r.getString(1);}}
                try(var s=db.prepareStatement("INSERT INTO government_transitions VALUES(?,?,?)")){s.setLong(1,target.town().civ());s.setString(2,government);s.setLong(3,now);s.executeUpdate();}
                try(var s=db.prepareStatement("UPDATE civilizations SET government='gov_anarchy' WHERE id=?")){s.setLong(1,target.town().civ());s.executeUpdate();}com.avrgaming.civcraft.modern.research.CivilizationScienceStore.cap(db,target.town().civ(),0);return "Правительство погружено в анархию.";
            }
            case "spy_sabotage" -> {
                var building=target.building();var health=BuildingDamageStore.health(db,building.id());if(health.hitpoints()==0)throw new IllegalArgumentException("Постройка уже разрушена.");
                try(var s=db.prepareStatement("INSERT INTO building_combat_health VALUES(?,0,?,'DESTROY_PENDING') ON CONFLICT(build_id) DO UPDATE SET hitpoints=0,phase='DESTROY_PENDING'")){s.setLong(1,building.id());s.setInt(2,health.maximum());s.executeUpdate();}
                try(var s=db.prepareStatement("UPDATE structure_builds SET status='WAR_DESTROY_PENDING' WHERE id=? AND status='COMPLETE'")){s.setLong(1,building.id());if(s.executeUpdate()!=1)throw new SQLException("Sabotage target changed");}return "Постройка #"+building.id()+" уничтожена саботажем.";
            }
            case "spy_sabotage_wonder" -> {
                if(snapshot==null)throw new SQLException("Missing unfinished wonder snapshot");
                com.avrgaming.civcraft.modern.war.BuildingRuinStore.sabotageWonder(db,target.building().id(),snapshot);return "Строящееся чудо #"+target.building().id()+" уничтожено саботажем. Стоимость стройки не возвращается.";
            }
            case "spy_pirate" -> {return "Торговый товар похищен из исходной рамки.";}
            default -> throw new IllegalArgumentException("Для этой цели саботаж недоступен.");
        }
    }
}
