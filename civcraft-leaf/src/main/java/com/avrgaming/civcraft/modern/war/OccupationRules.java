package com.avrgaming.civcraft.modern.war;
import java.sql.*;
public final class OccupationRules {
    private OccupationRules() {}
    public record Income(long town,long civilization,long discarded) {}
    public static void validatePenalty(double penalty){if(!Double.isFinite(penalty) || penalty<0 || penalty>1)throw new IllegalArgumentException("Captured penalty must be between zero and one");}
    public static Income income(long gross,double tax,boolean captured,double penalty) {
        validatePenalty(penalty);if(gross<0 || !Double.isFinite(tax) || tax<0 || tax>1)throw new IllegalArgumentException("Invalid town income");
        long tribute=Math.min(gross,Math.round(gross*tax)),net=gross-tribute;
        long local=captured?Math.min(net,Math.round(net*penalty)):net;
        return new Income(local,tribute,net-local);
    }
    public static boolean captured(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM town_conquests WHERE town_id=? AND mother_civ IS NOT NULL")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next();}}
    }
    public static String motherName(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT c.name FROM town_conquests tc JOIN civilizations c ON c.id=tc.mother_civ WHERE tc.town_id=?")){s.setLong(1,town);try(var r=s.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    public static void credit(Connection db,long town,long civ,Income income)throws SQLException {
        if(income.town()>0)com.avrgaming.civcraft.modern.town.UpkeepStore.deposit(db,"towns",town,income.town());
        if(income.civilization()>0)com.avrgaming.civcraft.modern.town.UpkeepStore.deposit(db,"civilizations",civ,income.civilization());
    }
    public static String government(Connection db,long town)throws SQLException {
        try(var s=db.prepareStatement("SELECT c.government,m.government,tc.mother_civ FROM towns t JOIN civilizations c ON c.id=t.civ_id LEFT JOIN town_conquests tc ON tc.town_id=t.id LEFT JOIN civilizations m ON m.id=tc.mother_civ WHERE t.id=?")) {
            s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing occupation town");String current=r.getString(1),mother=r.getString(2);return government(current,mother,r.getObject(3)!=null);}
        }
    }
    public static String government(String current,String mother,boolean captured) {
        if(captured && "gov_anarchy".equals(current))return mother!=null && !"gov_anarchy".equals(mother)?mother:"gov_tribalism";
        return current;
    }
}
