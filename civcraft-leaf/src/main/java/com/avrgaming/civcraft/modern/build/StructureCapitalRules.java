package com.avrgaming.civcraft.modern.build;
import java.sql.*;
import java.util.Set;
public final class StructureCapitalRules {
    private StructureCapitalRules() {}
    public static void require(Connection db,long town,String type)throws SQLException {
        if(!Set.of("s_capitol","s_townhall","w_colosseum","w_battledome").contains(type))return;
        boolean capital;
        try(var s=db.prepareStatement("SELECT COALESCE(c.capital_town_id,(SELECT MIN(first.id) FROM towns first WHERE first.civ_id=c.id))=t.id FROM towns t JOIN civilizations c ON c.id=t.civ_id WHERE t.id=?")){s.setLong(1,town);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Construction town not found");capital=r.getBoolean(1);}}
        if(type.equals("s_townhall")&&capital)throw new IllegalArgumentException("В столице строится капитолий, а не обычная ратуша.");
        if(!type.equals("s_townhall")&&!capital)throw new IllegalArgumentException("Эту постройку можно поставить только в столице цивилизации.");
        if(type.startsWith("w_"))try(var s=db.prepareStatement("SELECT 1 FROM structure_builds WHERE town_id=? AND structure_id=? AND status NOT IN ('DEMOLISHED','CANCELLED','DEMOLISH_PENDING','ROLLBACK_PENDING') LIMIT 1")){s.setLong(1,town);s.setString(2,type);try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Это чудо уже зарегистрировано в вашем городе.");}}
    }
}
