package com.avrgaming.civcraft.modern.diplomacy;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import com.avrgaming.civcraft.modern.war.*;
public final class WarDeclarationRules {
    private WarDeclarationRules() {}
    public static boolean helpsAlly(Connection db,long from,long enemy)throws SQLException {
        try(var s=db.prepareStatement("""
            SELECT 1 FROM diplomacy_relations a JOIN diplomacy_relations w
            ON w.status='WAR' AND w.aggressor=? AND (w.first_civ=? OR w.second_civ=?)
            WHERE a.status='ALLY' AND (a.first_civ=? OR a.second_civ=?)
              AND CASE WHEN a.first_civ=? THEN a.second_civ ELSE a.first_civ END
                = CASE WHEN w.first_civ=? THEN w.second_civ ELSE w.first_civ END LIMIT 1
            """)){s.setLong(1,enemy);s.setLong(2,enemy);s.setLong(3,enemy);s.setLong(4,from);s.setLong(5,from);s.setLong(6,from);s.setLong(7,enemy);try(var r=s.executeQuery()){return r.next();}}
    }
    public static DiplomacyStore.Relation declare(Connection db,long from,long to,UUID actor,DiplomacyStore.Status status,WarCalendar calendar,boolean casual,long now)throws SQLException {
        DiplomacyStore.authority(db,from,actor);var before=DiplomacyStore.relation(db,from,to);WarClockStore.requireDiplomacyOpen(db,calendar,now);
        try(var s=db.prepareStatement("SELECT 1 FROM administrative_civilizations WHERE civ_id=?")){s.setLong(1,to);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Нельзя объявлять враждебность или войну административной цивилизации.");}}
        if(status!=DiplomacyStore.Status.WAR&&status!=DiplomacyStore.Status.HOSTILE)throw new IllegalArgumentException("Объявить можно HOSTILE или WAR.");
        if(before.status()==status)throw new IllegalArgumentException("Эти отношения уже установлены.");
        if(status==DiplomacyStore.Status.HOSTILE&&before.status()==DiplomacyStore.Status.WAR)throw new IllegalArgumentException("Нельзя заменить войну враждебностью.");
        if(status==DiplomacyStore.Status.WAR) {
            if(casual)throw new IllegalArgumentException("В мирном режиме объявление войны отключено.");
            var instant=Instant.ofEpochMilli(now);
            if(calendar.declarationClosed(instant)) {
                if(!helpsAlly(db,from,to))throw new IllegalArgumentException("Слишком близко к боевому периоду для обычного объявления войны.");
                if(calendar.allyDeclarationClosed(instant))throw new IllegalArgumentException("Слишком близко к боевому периоду даже для помощи союзнику.");
            }
            try(var s=db.prepareStatement("SELECT debt FROM civilizations WHERE id=?")){s.setLong(1,from);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Declaring civilization missing");if(r.getLong(1)>0)throw new IllegalArgumentException("Цивилизация в долгу: объявить войну нельзя.");}}
        }
        DiplomacyStore.change(db,from,to,actor,status,from,now);
        try(var s=db.prepareStatement("UPDATE diplomacy_requests SET state='CANCELLED' WHERE state='PENDING' AND ((from_civ=? AND to_civ=?) OR (from_civ=? AND to_civ=?))")){s.setLong(1,from);s.setLong(2,to);s.setLong(3,to);s.setLong(4,from);s.executeUpdate();}
        return DiplomacyStore.relation(db,from,to);
    }
}
