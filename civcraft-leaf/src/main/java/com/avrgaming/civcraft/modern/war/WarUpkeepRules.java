package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
public record WarUpkeepRules(double base,double perPoint) {
    public record Cost(long civ,long enemy,long ownScore,long enemyScore,boolean doubled,double coins) {}
    public WarUpkeepRules {if(!Double.isFinite(base)||base<0||!Double.isFinite(perPoint)||perPoint<0)throw new IllegalArgumentException("Неверные правила военного содержания");}
    public static WarUpkeepRules load(java.io.File file){var config=YamlConfiguration.loadConfiguration(file);return new WarUpkeepRules(config.getDouble("war.upkeep_per_war",3000),config.getDouble("war.upkeep_per_war_multiplier",.005));}
    public static double amount(double base,double multiplier,long own,long enemy,boolean doubled){double result=(base+Math.max(0,(double)own-enemy)*multiplier)*(doubled?2:1);if(!Double.isFinite(result)||result<0||result>1e12)throw new IllegalArgumentException("Переполнение стоимости военного содержания");return result;}
    public List<Cost> plan(Connection db)throws SQLException {
        record Pair(long civ,long enemy) {}
        var pairs=new ArrayList<Pair>();try(var s=db.createStatement();var r=s.executeQuery("""
            SELECT aggressor,CASE WHEN first_civ=aggressor THEN second_civ ELSE first_civ END AS enemy
            FROM diplomacy_relations d WHERE status='WAR' AND aggressor IN (first_civ,second_civ)
              AND NOT EXISTS(SELECT 1 FROM administrative_civilizations a WHERE a.civ_id=d.aggressor)
            ORDER BY aggressor,enemy
            """)){while(r.next())pairs.add(new Pair(r.getLong(1),r.getLong(2)));}
        var result=new ArrayList<Cost>();var doubled=new HashSet<Long>();
        for(var pair:pairs) {
            try(var s=db.prepareStatement("""
                SELECT 1 FROM structure_builds b JOIN towns t ON t.id=b.town_id
                WHERE b.structure_id='w_notre_dame' AND b.status='COMPLETE' AND (t.civ_id=? OR EXISTS(
                  SELECT 1 FROM diplomacy_relations d WHERE d.status='ALLY'
                  AND ((d.first_civ=? AND d.second_civ=t.civ_id) OR (d.second_civ=? AND d.first_civ=t.civ_id)))) LIMIT 1
                """)){s.setLong(1,pair.enemy());s.setLong(2,pair.enemy());s.setLong(3,pair.enemy());try(var r=s.executeQuery()){if(r.next())doubled.add(pair.civ());}}
            long own=LegacyScoreStore.score(db,pair.civ()),enemy=LegacyScoreStore.score(db,pair.enemy());boolean penalty=doubled.contains(pair.civ());result.add(new Cost(pair.civ(),pair.enemy(),own,enemy,penalty,amount(base,perPoint,own,enemy,penalty)));
        }return List.copyOf(result);
    }
    public static void install(Connection db)throws SQLException {LegacyScoreStore.install(db);try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS war_upkeep_payments(day TEXT NOT NULL,civ_id INTEGER NOT NULL,enemy_id INTEGER NOT NULL,own_score INTEGER NOT NULL,enemy_score INTEGER NOT NULL,doubled INTEGER NOT NULL,coins REAL NOT NULL,PRIMARY KEY(day,civ_id,enemy_id))");}}
    public static void audit(Connection db,String day,List<Cost> costs)throws SQLException {for(var cost:costs)try(var s=db.prepareStatement("INSERT INTO war_upkeep_payments VALUES(?,?,?,?,?,?,?)")){s.setString(1,day);s.setLong(2,cost.civ());s.setLong(3,cost.enemy());s.setLong(4,cost.ownScore());s.setLong(5,cost.enemyScore());s.setBoolean(6,cost.doubled());s.setDouble(7,cost.coins());s.executeUpdate();}}
}
