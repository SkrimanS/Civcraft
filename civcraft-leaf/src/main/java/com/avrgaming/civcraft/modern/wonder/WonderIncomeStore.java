package com.avrgaming.civcraft.modern.wonder;
import java.sql.*;
import java.util.*;
import com.avrgaming.civcraft.modern.town.*;
/** The daily marker, treasury credits and historical audit commit in the caller's transaction. */
public final class WonderIncomeStore {
    private WonderIncomeStore() {}
    public record Credit(long build,long town,long civ,String type,long units,long coins) {}
    public static void install(Connection db)throws SQLException {
        NotreDameIncomeStore.install(db);
        BankDailyStore.install(db);try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS wonder_daily_income(day TEXT NOT NULL,build_id INTEGER NOT NULL,town_id INTEGER NOT NULL,civ_id INTEGER NOT NULL,type TEXT NOT NULL,units INTEGER NOT NULL,coins INTEGER NOT NULL,PRIMARY KEY(day,build_id))");}
    }
    public static List<Credit> pay(Connection db,String day,long now,WonderEconomy economy)throws SQLException {
        return pay(db,day,now,economy,.5);
    }
    public static List<Credit> pay(Connection db,String day,long now,WonderEconomy economy,double capturedPenalty)throws SQLException {
        try(var s=db.prepareStatement("INSERT OR IGNORE INTO daily_economy_events VALUES('wonder_income',?,?)")){s.setString(1,day);s.setLong(2,now);if(s.executeUpdate()!=1)return List.of();}
        var planned=new ArrayList<Credit>();
        try(var s=db.createStatement();var r=s.executeQuery("""
            SELECT b.id,t.id,t.civ_id,b.structure_id,
              CASE WHEN b.structure_id='w_colossus' THEN
                (SELECT COUNT(*) FROM culture_regions cr JOIN towns ct ON ct.id=cr.town_id WHERE ct.civ_id=t.civ_id)
              ELSE (SELECT COUNT(*) FROM towns) END
            FROM structure_builds b JOIN towns t ON t.id=b.town_id JOIN civilizations c ON c.id=t.civ_id
            WHERE b.structure_id IN ('w_colossus','w_colosseum') AND b.status IN ('QUEUED','BUILDING','COMPLETE') ORDER BY b.id
            """)) {
            while(r.next()){String type=r.getString(4);long units=r.getLong(5);planned.add(new Credit(r.getLong(1),r.getLong(2),r.getLong(3),type,units,WonderEconomy.coins(type.equals("w_colossus")?economy.cultureCoins():economy.worldTownCoins(),units)));}
        }
        planned.addAll(NotreDameIncomeStore.plan(db,economy.peacefulTownCoins()));
        for(var credit:planned) {
            if(credit.type().equals("w_notre_dame"))NotreDameIncomeStore.deposit(db,day,credit,capturedPenalty);
            else if(credit.coins()>0)UpkeepStore.deposit(db,"civilizations",credit.civ(),credit.coins());
            try(var s=db.prepareStatement("INSERT INTO wonder_daily_income VALUES(?,?,?,?,?,?,?)")){s.setString(1,day);s.setLong(2,credit.build());s.setLong(3,credit.town());s.setLong(4,credit.civ());s.setString(5,credit.type());s.setLong(6,credit.units());s.setLong(7,credit.coins());s.executeUpdate();}
        }
        return List.copyOf(planned);
    }
}
