package com.avrgaming.civcraft.modern.war;
import java.io.File;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
public final class RevolutionRules {
    private final Map<String,Long> technologies,buildings;
    private final long residentPoints,claimPoints,culturePoints,coinsPerPoint;
    private final double baseCost,perTown,perPoint,maximum;
    private final int cooldownDays;
    public RevolutionRules(File folder) {
        var score=YamlConfiguration.loadConfiguration(new File(folder,"score.yml"));var war=YamlConfiguration.loadConfiguration(new File(folder,"war.yml"));var civ=YamlConfiguration.loadConfiguration(new File(folder,"civ.yml"));
        technologies=points(new File(folder,"techs.yml"),"techs");var map=new HashMap<>(points(new File(folder,"structures.yml"),"structures"));map.putAll(points(new File(folder,"wonders.yml"),"wonders"));buildings=Map.copyOf(map);
        residentPoints=score.getLong("town_scores.resident",5000);claimPoints=score.getLong("town_scores.town_chunk",200);culturePoints=score.getLong("town_scores.culture_chunk",100);coinsPerPoint=score.getLong("coins_per_point",5);
        baseCost=war.getDouble("revolution.base_cost",50000);perTown=war.getDouble("revolution.coins_per_town",10000);perPoint=war.getDouble("revolution.coins_per_point",.1);maximum=war.getDouble("revolution.maximum_fee",1000000);cooldownDays=civ.getInt("civ.revolution_cooldown",2);
        if(coinsPerPoint<1 || residentPoints<0 || claimPoints<0 || culturePoints<0 || cooldownDays<0 || cooldownDays>3650)throw new IllegalArgumentException("Invalid revolution score rules");
        for(double value:new double[]{baseCost,perTown,perPoint,maximum})if(!Double.isFinite(value) || value<0 || value>1e12)throw new IllegalArgumentException("Invalid revolution fees");
    }
    private static Map<String,Long> points(File file,String key) {
        var out=new HashMap<String,Long>();for(var row:YamlConfiguration.loadConfiguration(file).getMapList(key)) {
            Object id=row.get("id"),value=row.get("points");if(id==null)continue;long n=value instanceof Number number?number.longValue():0;
            if(n<0)throw new IllegalArgumentException("Negative score points: "+id);out.put(id.toString(),n);
        }return Map.copyOf(out);
    }
    public int cooldownDays(){return cooldownDays;}
    public boolean cooldownPassed(long conquered,long now,ZoneId zone){return Instant.ofEpochMilli(now).isAfter(Instant.ofEpochMilli(conquered).atZone(zone).plusDays(cooldownDays).toInstant());}
    public static long price(double base,double towns,double perTown,double points,double perPoint,double cap) {
        double fee=Math.min(cap,base+towns*perTown+points*perPoint);if(!Double.isFinite(fee) || fee<0 || fee>1e12)throw new IllegalArgumentException("Unsupported revolution fee");
        // Town balances are whole coins. Never approve a balance below the original fractional fee.
        return (long)Math.ceil(fee);
    }
    public record Fee(long coins,long points,int towns) {}
    public Fee fee(Connection db,long mother)throws SQLException {
        long points=0;int towns=0;
        try(var s=db.prepareStatement("SELECT tech_id FROM civ_technologies WHERE civ_id=?")){s.setLong(1,mother);try(var r=s.executeQuery()){while(r.next())points=Math.addExact(points,technologies.getOrDefault(r.getString(1),0L));}}
        try(var s=db.prepareStatement("SELECT t.id,t.coins FROM towns t JOIN town_conquests tc ON tc.town_id=t.id WHERE tc.mother_civ=? ORDER BY t.id")) {
            s.setLong(1,mother);try(var r=s.executeQuery()){while(r.next()) {
                long town=r.getLong(1),score=0;towns++;
                for(var source:List.of(new String[]{"residents","town_id",Long.toString(residentPoints)},new String[]{"town_claims","town_id",Long.toString(claimPoints)},new String[]{"culture_regions","town_id",Long.toString(culturePoints)}))try(var q=db.prepareStatement("SELECT COUNT(*) FROM "+source[0]+" WHERE "+source[1]+"=?")){q.setLong(1,town);try(var count=q.executeQuery()){count.next();score=Math.addExact(score,Math.multiplyExact(count.getLong(1),Long.parseLong(source[2])));}}
                try(var q=db.prepareStatement("SELECT structure_id FROM structure_builds WHERE town_id=? AND status IN ('QUEUED','BUILDING','COMPLETE','WAR_DESTROY_PENDING','WAR_DESTROYED','WAR_REPAIR_PENDING')")){q.setLong(1,town);try(var b=q.executeQuery()){while(b.next())score=Math.addExact(score,buildings.getOrDefault(b.getString(1),0L));}}
                score=Math.addExact(score,Math.max(0,r.getLong(2))/coinsPerPoint);points=Math.addExact(points,score);
            }}
        }return new Fee(price(baseCost,towns,perTown,points,perPoint,maximum),points,towns);
    }
}
