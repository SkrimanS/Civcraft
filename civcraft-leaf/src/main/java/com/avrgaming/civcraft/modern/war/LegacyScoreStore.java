package com.avrgaming.civcraft.modern.war;
import java.sql.*;
import java.util.*;
/** Minute snapshots preserve the legacy leaderboard lookup used by war upkeep. */
public final class LegacyScoreStore {
    private LegacyScoreStore() {}
    public static void install(Connection db)throws SQLException {try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS legacy_civ_scores(civ_id INTEGER PRIMARY KEY REFERENCES civilizations(id) ON DELETE CASCADE,points INTEGER NOT NULL,ranked INTEGER NOT NULL,checked_at INTEGER NOT NULL)");}}
    public static void refresh(Connection db,RevolutionRules rules,long now)throws SQLException {
        boolean endgame;try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='endgame_round'")){endgame=r.next();}
        if(endgame && com.avrgaming.civcraft.modern.endgame.EndGameStore.winner(db)!=null)return;
        var ids=new ArrayList<Long>();try(var s=db.createStatement();var r=s.executeQuery("SELECT id FROM civilizations c WHERE NOT EXISTS(SELECT 1 FROM administrative_civilizations a WHERE a.civ_id=c.id) AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"civilizations","c.id")+" ORDER BY id")){while(r.next())ids.add(r.getLong(1));}
        var scores=new LinkedHashMap<Long,Long>();var ranked=new TreeMap<Long,Long>();for(long id:ids){long score=rules.civilizationScore(db,id);scores.put(id,score);ranked.put(score,id);}
        try(var s=db.createStatement()){s.executeUpdate("DELETE FROM legacy_civ_scores WHERE "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"civilizations","legacy_civ_scores.civ_id"));}
        for(var entry:scores.entrySet())try(var s=db.prepareStatement("INSERT INTO legacy_civ_scores VALUES(?,?,?,?)")){s.setLong(1,entry.getKey());s.setLong(2,entry.getValue());s.setBoolean(3,ranked.get(entry.getValue()).equals(entry.getKey()));s.setLong(4,now);s.executeUpdate();}
    }
    public static long score(Connection db,long civ)throws SQLException {try(var s=db.prepareStatement("SELECT points FROM legacy_civ_scores WHERE civ_id=? AND ranked=1 AND "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"civilizations","legacy_civ_scores.civ_id"))){s.setLong(1,civ);try(var r=s.executeQuery()){return r.next()?r.getLong(1):0;}}}
}
