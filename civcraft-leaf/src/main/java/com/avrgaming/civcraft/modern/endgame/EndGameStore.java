package com.avrgaming.civcraft.modern.endgame;

import java.sql.*;
import java.time.*;
import java.util.*;
import com.avrgaming.civcraft.modern.research.CivilizationScienceStore;

/** All round results, daily countdowns and ballots commit in their caller's transaction. */
public final class EndGameStore {
    private EndGameStore() {}
    public record Winner(long civ,String name,String condition,long at) {}
    public record Progress(long civ,String name,String condition,int held) {}
    public static void install(Connection db,long now,String scienceTech)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS endgame_round(singleton INTEGER PRIMARY KEY CHECK(singleton=1),started_at INTEGER NOT NULL,winner_civ INTEGER,winner_name TEXT,winner_condition TEXT,won_at INTEGER,science_tech TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS endgame_days(day TEXT PRIMARY KEY,checked_at INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS diplomatic_votes(civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE,votes INTEGER NOT NULL CHECK(votes>=0))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS diplomatic_voters(resident_uuid TEXT PRIMARY KEY REFERENCES residents(uuid) ON DELETE CASCADE,last_vote INTEGER NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS endgame_final_scores(civ_id INTEGER PRIMARY KEY,name TEXT NOT NULL,points INTEGER NOT NULL,ranked INTEGER NOT NULL)");
        }
        try(var s=db.prepareStatement("INSERT INTO endgame_round(singleton,started_at,science_tech) VALUES(1,?,?) ON CONFLICT(singleton) DO UPDATE SET science_tech=excluded.science_tech WHERE winner_civ IS NULL")){s.setLong(1,now);s.setString(2,scienceTech);s.executeUpdate();}
    }
    public static Winner winner(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT winner_civ,winner_name,winner_condition,won_at FROM endgame_round WHERE singleton=1 AND winner_civ IS NOT NULL")){return r.next()?new Winner(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4)):null;}
    }
    public static List<Progress> progress(Connection db)throws SQLException {
        var out=new ArrayList<Progress>();try(var s=db.createStatement();var r=s.executeQuery("SELECT p.civ_id,c.name,p.condition_id,p.days_held FROM victory_countdowns p JOIN civilizations c ON c.id=p.civ_id ORDER BY p.civ_id,p.condition_id")){while(r.next())out.add(new Progress(r.getLong(1),r.getString(2),r.getString(3),r.getInt(4)));}return List.copyOf(out);
    }
    public static boolean activeWonder(Connection db,Long civ,String type,boolean originalTown)throws SQLException {
        String query="SELECT 1 FROM active_structure_builds b JOIN towns t ON t.id=b.town_id LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE b.structure_id=? AND COALESCE(h.hitpoints,b.max_hitpoints)>0"+(civ==null?"":" AND t.civ_id=?")+(originalTown?" AND NOT EXISTS(SELECT 1 FROM town_conquests tc WHERE tc.town_id=t.id AND tc.mother_civ IS NOT NULL)":"")+" LIMIT 1";
        try(var s=db.prepareStatement(query)){s.setString(1,type);if(civ!=null)s.setLong(2,civ);try(var r=s.executeQuery()){return r.next();}}
    }
    public static double conqueredFraction(Connection db,long civ)throws SQLException {
        long total=0,captured=1;
        try(var s=db.createStatement();var r=s.executeQuery("SELECT c.id,cc.civ_id,t.civ_id FROM civilizations c LEFT JOIN civ_conquests cc ON cc.civ_id=c.id LEFT JOIN towns t ON t.id=c.capital_town_id ORDER BY c.id")) {
            while(r.next()){boolean dormant=r.getObject(2)!=null;if(dormant && r.getObject(3)==null)continue;total++;if(dormant && r.getLong(3)==civ)captured++;}
        }
        return total==0?0:captured/(double)total;
    }
    public static boolean meets(Connection db,long civ,EndGameRules.Condition condition,EndGameRules rules,long now,ZoneId zone)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM administrative_civilizations WHERE civ_id=? UNION ALL SELECT 1 FROM civ_conquests WHERE civ_id=? UNION ALL SELECT 1 FROM war_defeated_civs WHERE civ_id=?")){s.setLong(1,civ);s.setLong(2,civ);s.setLong(3,civ);try(var r=s.executeQuery()){if(r.next())return false;}}
        return switch(condition.id()) {
            case "end_culture" -> {
                int towns=0;
                try(var s=db.prepareStatement("SELECT accumulated_culture FROM towns t WHERE civ_id=? AND NOT EXISTS(SELECT 1 FROM town_conquests tc WHERE tc.town_id=t.id AND tc.mother_civ IS NOT NULL)")){s.setLong(1,civ);try(var r=s.executeQuery()){while(r.next())if(rules.cultureLevel(r.getLong(1))>=condition.integer("culture_level"))towns++;}}
                long wonders;try(var s=db.prepareStatement("SELECT COUNT(*) FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE t.civ_id=? AND b.structure_id GLOB 'w_*' AND b.status NOT IN ('DEMOLISHED','CANCELLED','DEMOLISH_PENDING','ROLLBACK_PENDING')")){s.setLong(1,civ);try(var r=s.executeQuery()){r.next();wonders=r.getLong(1);}}
                yield towns>=condition.integer("towns") && wonders>=condition.integer("wonders");
            }
            case "end_conquest" -> {
                long started;try(var s=db.createStatement();var r=s.executeQuery("SELECT started_at FROM endgame_round WHERE singleton=1")){if(!r.next())throw new SQLException("Missing round start");started=r.getLong(1);}
                boolean oldEnough=Instant.ofEpochMilli(now).isAfter(Instant.ofEpochMilli(started).atZone(zone).plusDays(condition.integer("days_after_start")).toInstant());
                yield oldEnough && conqueredFraction(db,civ)>=condition.decimal(activeWonder(db,civ,"w_chichen_itza",true)?"percent_captured_with_wonder":"percent_captured");
            }
            case "end_science" -> {
                boolean researched;try(var s=db.prepareStatement("SELECT 1 FROM civ_technologies WHERE civ_id=? AND tech_id=?")){s.setLong(1,civ);s.setString(2,rules.scienceTech());try(var r=s.executeQuery()){researched=r.next();}}
                yield researched && activeWonder(db,civ,"w_greatlibrary",true);
            }
            case "end_diplomacy" -> activeWonder(db,civ,"w_council_of_eight",true);
            default -> throw new SQLException("Unsupported victory: "+condition.id());
        };
    }
    private static boolean finalCheck(Connection db,long civ,String condition)throws SQLException {
        if(condition.equals("end_science"))try(var s=db.createStatement();var r=s.executeQuery("SELECT s.civ_id FROM civilization_science s WHERE s.extra_beakers>0 AND NOT EXISTS(SELECT 1 FROM civ_conquests cc WHERE cc.civ_id=s.civ_id) ORDER BY s.extra_beakers DESC,s.civ_id LIMIT 1")){return r.next() && r.getLong(1)==civ;}
        if(condition.equals("end_diplomacy"))try(var s=db.prepareStatement("SELECT 1 FROM diplomatic_votes v WHERE v.votes>COALESCE((SELECT votes FROM diplomatic_votes WHERE civ_id=?),0) AND NOT EXISTS(SELECT 1 FROM civ_conquests cc WHERE cc.civ_id=v.civ_id) LIMIT 1")){s.setLong(1,civ);try(var r=s.executeQuery()){return !r.next();}}
        return true;
    }
    public static List<String> daily(Connection db,String day,long now,ZoneId zone,EndGameRules rules,boolean casual)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Victory check requires a transaction");
        if(casual)return List.of();
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO endgame_days VALUES(?,?)")){s.setString(1,day);s.setLong(2,now);if(s.executeUpdate()==0)return List.of();}
        var previous=winner(db);if(previous!=null)return List.of(announcement(previous));
        com.avrgaming.civcraft.modern.war.CapitalStore.seed(db);
        var names=new LinkedHashMap<Long,String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id,name FROM civilizations ORDER BY id")){while(r.next())names.put(r.getLong(1),r.getString(2));}
        var messages=new ArrayList<String>();
        // Evaluate everybody before advancing anyone, so a rival's stale countdown cannot win.
        for(long civ:names.keySet())for(var condition:rules.conditions())if(!meets(db,civ,condition,rules,now,zone)) {
            try(var s=db.prepareStatement("DELETE FROM victory_countdowns WHERE civ_id=? AND condition_id=?")){s.setLong(1,civ);s.setString(2,condition.id());if(s.executeUpdate()>0){messages.add("Цивилизация "+names.get(civ)+" потеряла условия победы: "+EndGameRules.name(condition.id())+". Отсчёт сброшен.");if(condition.id().equals("end_diplomacy"))clearVotes(db,civ);}}
        }
        for(long civ:names.keySet())for(var condition:rules.conditions()) {
            if(!meets(db,civ,condition,rules,now,zone))continue;
            int days=0;try(var s=db.prepareStatement("SELECT days_held,last_day FROM victory_countdowns WHERE civ_id=? AND condition_id=?")){s.setLong(1,civ);s.setString(2,condition.id());try(var r=s.executeQuery()){if(r.next()){if(day.equals(r.getString(2)))continue;days=Math.min(condition.days(),r.getInt(1)+1);}}}
            try(var s=db.prepareStatement("INSERT INTO victory_countdowns VALUES(?,?,?,?) ON CONFLICT(civ_id,condition_id) DO UPDATE SET days_held=excluded.days_held,last_day=excluded.last_day")){s.setLong(1,civ);s.setString(2,condition.id());s.setInt(3,days);s.setString(4,day);s.executeUpdate();}
            if(days>=condition.days()) {
                if(finalCheck(db,civ,condition.id())) {
                    try(var s=db.prepareStatement("UPDATE endgame_round SET winner_civ=?,winner_name=?,winner_condition=?,won_at=? WHERE singleton=1 AND winner_civ IS NULL")){s.setLong(1,civ);s.setString(2,names.get(civ));s.setString(3,condition.id());s.setLong(4,now);if(s.executeUpdate()!=1)throw new SQLException("Round already has a winner");}
                    try(var s=db.createStatement()){s.executeUpdate("INSERT INTO endgame_final_scores SELECT s.civ_id,c.name,s.points,s.ranked FROM legacy_civ_scores s JOIN civilizations c ON c.id=s.civ_id");}
                    messages.add(announcement(winner(db)));return List.copyOf(messages);
                }
                messages.add("Цивилизация "+names.get(civ)+" удержала условия победы «"+EndGameRules.name(condition.id())+"», но ещё не лидирует по "+(condition.id().equals("end_science")?"накопленным колбам.":"голосам."));
            } else messages.add("Цивилизация "+names.get(civ)+": "+EndGameRules.name(condition.id())+" победа через "+(condition.days()-days)+" дн. удержания условий.");
        }
        return List.copyOf(messages);
    }
    public static String announcement(Winner winner){return "Победила цивилизация "+winner.name()+"! "+EndGameRules.name(winner.condition())+" победа. Итоговые очки сохранены.";}
    private static void clearVotes(Connection db,long civ)throws SQLException {try(var s=db.prepareStatement("DELETE FROM diplomatic_votes WHERE civ_id=?")){s.setLong(1,civ);s.executeUpdate();}}
    public static void vote(Connection db,UUID actor,long target,long now,long cooldown)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM residents WHERE uuid=? AND town_id IS NOT NULL")){s.setString(1,actor.toString());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Для голосования нужно состоять в городе.");}}
        try(var s=db.prepareStatement("SELECT 1 FROM civilizations c WHERE c.id=? AND NOT EXISTS(SELECT 1 FROM civ_conquests cc WHERE cc.civ_id=c.id)")){s.setLong(1,target);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Цивилизация не найдена или завоёвана.");}}
        if(!activeWonder(db,null,"w_council_of_eight",false))throw new IllegalArgumentException("Голосование откроется после постройки Совета Восьми.");
        try(var s=db.prepareStatement("SELECT last_vote FROM diplomatic_voters WHERE resident_uuid=?")){s.setString(1,actor.toString());try(var r=s.executeQuery()){if(r.next() && now-r.getLong(1)<=cooldown)throw new IllegalArgumentException("Повторное голосование доступно через "+Math.max(1,(cooldown-(now-r.getLong(1))+59999)/60000)+" мин.");}}
        try(var s=db.prepareStatement("INSERT INTO diplomatic_voters VALUES(?,?) ON CONFLICT(resident_uuid) DO UPDATE SET last_vote=excluded.last_vote")){s.setString(1,actor.toString());s.setLong(2,now);s.executeUpdate();}
        try(var s=db.prepareStatement("INSERT INTO diplomatic_votes VALUES(?,1) ON CONFLICT(civ_id) DO UPDATE SET votes=votes+1 WHERE votes<?")){s.setLong(1,target);s.setLong(2,Long.MAX_VALUE);if(s.executeUpdate()!=1)throw new SQLException("Vote count overflow");}
    }
    /** Invoked at the capitol's last control point, before captured towns change hands. */
    public static void warDefeat(Connection db,long civ)throws SQLException {
        try(var s=db.prepareStatement("DELETE FROM victory_countdowns WHERE civ_id=?")){s.setLong(1,civ);s.executeUpdate();}
        clearVotes(db,civ);
        try(var s=db.prepareStatement("UPDATE civilization_science SET extra_beakers=0 WHERE civ_id=?")){s.setLong(1,civ);s.executeUpdate();}
        try(var s=db.prepareStatement("DELETE FROM civ_technologies WHERE civ_id=? AND tech_id=(SELECT science_tech FROM endgame_round WHERE singleton=1)")){s.setLong(1,civ);s.executeUpdate();}
        var councils=new ArrayList<Long>();try(var s=db.prepareStatement("SELECT b.id FROM active_structure_builds b JOIN towns t ON t.id=b.town_id LEFT JOIN building_combat_health h ON h.build_id=b.id WHERE t.civ_id=? AND b.structure_id='w_council_of_eight' AND COALESCE(h.hitpoints,b.max_hitpoints)>0 AND NOT EXISTS(SELECT 1 FROM town_conquests tc WHERE tc.town_id=t.id AND tc.mother_civ IS NOT NULL)")){s.setLong(1,civ);try(var r=s.executeQuery()){while(r.next())councils.add(r.getLong(1));}}
        for(long build:councils)com.avrgaming.civcraft.modern.war.BuildingRuinStore.destroyCompletedWonder(db,build);
    }
}
