package com.avrgaming.civcraft.modern.build;
import java.sql.*;
import java.util.*;
public final class StructureSpacingRules {
    private StructureSpacingRules() {}
    public record Existing(long id,String type,String path,int x,int y,int z) {}
    public static String family(String type){if(type==null)return "";return switch(type){case "s_cannontower","s_cannonship"->"cannon";case "s_teslatower"->"tesla";default->"";};}
    public static List<Existing> existing(Connection db,long town,String type,String world)throws SQLException {
        String family=family(type);if(family.isEmpty())return List.of();var result=new ArrayList<Existing>();
        try(var s=db.prepareStatement("SELECT b.id,b.structure_id,b.template_path,b.x,b.y,b.z FROM structure_builds b JOIN towns existing ON existing.id=b.town_id JOIN towns target ON target.civ_id=existing.civ_id WHERE target.id=? AND b.world=? AND b.status NOT IN ('DEMOLISHED','CANCELLED','DEMOLISH_PENDING','ROLLBACK_PENDING') ORDER BY b.id")){s.setLong(1,town);s.setString(2,world);try(var r=s.executeQuery()){while(r.next())if(family(r.getString(2)).equals(family))result.add(new Existing(r.getLong(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6)));}}return result;
    }
    public static void requireDistance(double x,double y,double z,double otherX,double otherY,double otherZ,double minimum) {
        if(!Double.isFinite(minimum)||minimum<0)throw new IllegalArgumentException("Invalid structure spacing");
        double squared=Math.pow(x-otherX,2)+Math.pow(y-otherY,2)+Math.pow(z-otherZ,2);
        if(squared<=minimum*minimum)throw new IllegalArgumentException("Слишком близко к военному сооружению вашей цивилизации. Расстояние между центрами должно быть больше "+minimum+" блоков.");
    }
}
