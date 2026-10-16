package com.avrgaming.civcraft.modern.espionage;

import java.sql.*;
import org.bukkit.Location;

/** Resolve again at completion; a delay never authorizes a different civilization's target. */
public final class MissionTargets {
    private MissionTargets() {}
    public record Town(long id,long civ) {}
    public record Building(long id,long town,String type,String status,String world,int x,int y,int z,String templatePath) {
        public double distanceSquared(Location at){double dx=x-at.getX(),dy=y-at.getY(),dz=z-at.getZ();return world.equals(at.getWorld().getName())?dx*dx+dy*dy+dz*dz:Double.POSITIVE_INFINITY;}
        public double centerDistanceSquared(Location at)throws SQLException {var offset=com.avrgaming.civcraft.modern.build.LegacyGeometry.center(templatePath);double dx=x+offset.x()-at.getX(),dy=y+offset.y()-at.getY(),dz=z+offset.z()-at.getZ();return world.equals(at.getWorld().getName())?dx*dx+dy*dy+dz*dz:Double.POSITIVE_INFINITY;}
    }
    public record Target(Town town,Building building,MissionCatalog.Mission mission) {}
    public static Town town(Connection db,Location at,boolean claimed)throws SQLException {
        String table=claimed?"town_claims":"culture_regions";
        try(var s=db.prepareStatement("SELECT t.id,t.civ_id FROM "+table+" r JOIN towns t ON t.id=r.town_id WHERE r.world=? AND r.chunk_x=? AND r.chunk_z=?")){s.setString(1,at.getWorld().getName());s.setInt(2,at.getBlockX()>>4);s.setInt(3,at.getBlockZ()>>4);try(var r=s.executeQuery()){return r.next()?new Town(r.getLong(1),r.getLong(2)):null;}}
    }
    public static Building nearest(Connection db,Town town,Location at,boolean wholeCiv,String type)throws SQLException {
        return nearest(db,town,at,wholeCiv,type,false);
    }
    public static Building nearest(Connection db,Town town,Location at,boolean wholeCiv,String type,boolean wonders)throws SQLException {
        Building best=null;double distance=Double.POSITIVE_INFINITY;
        try(var s=db.prepareStatement("SELECT b.id,b.town_id,b.structure_id,b.status,b.world,b.x,b.y,b.z,b.template_path FROM structure_builds b JOIN towns t ON t.id=b.town_id WHERE "+(wholeCiv?"t.civ_id":"t.id")+"=? AND b.world=? AND b.status NOT IN ('CANCELLED','DEMOLISHED','ROLLBACK_PENDING','DEMOLISH_PENDING')"+(wonders?"":" AND b.structure_id NOT GLOB 'w_*'")+(type==null?"":" AND b.structure_id=?"))){s.setLong(1,wholeCiv?town.civ():town.id());s.setString(2,at.getWorld().getName());if(type!=null)s.setString(3,type);try(var r=s.executeQuery()){while(r.next()){var b=new Building(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getInt(7),r.getInt(8),r.getString(9));double next=b.centerDistanceSquared(at);if(next<distance){best=b;distance=next;}}}}
        return best;
    }
    public static boolean wonder(Connection db,long civ,String type)throws SQLException {
        try(var s=db.prepareStatement("SELECT 1 FROM active_structure_builds b JOIN towns t ON t.id=b.town_id WHERE t.civ_id=? AND b.structure_id=?")){s.setLong(1,civ);s.setString(2,type);try(var r=s.executeQuery()){return r.next();}}
    }
    public static Target validate(Connection db,Location at,long sourceCiv,MissionCatalog.Mission mission,MissionCatalog catalog)throws SQLException {
        boolean culture=mission.id().equals("spy_sabotage") || mission.id().equals("spy_pirate");var town=town(db,at,!culture);
        if(town==null || town.civ()==sourceCiv)throw new IllegalArgumentException("Миссия требует территории другой цивилизации.");
        Building building=null;
        switch(mission.id()) {
            case "spy_investigate_town" -> { }
            case "spy_steal_treasury" -> {
                building=nearest(db,town,at,false,"s_townhall");var capitol=nearest(db,town,at,false,"s_capitol");if(building==null || capitol!=null && capitol.distanceSquared(at)<building.distanceSquared(at))building=capitol;
                if(building==null)throw new IllegalArgumentException("В городе нет ратуши.");
            }
            case "spy_poison_granary" -> {
                building=nearest(db,town,at,false,null);if(building==null || !building.type().equals("s_granary"))throw new IllegalArgumentException("Ближайшей постройкой должен быть амбар.");
                if(com.avrgaming.civcraft.modern.town.GranaryPoisonStore.remaining(db,town.id())>0)throw new IllegalArgumentException("Амбар города уже отравлен.");
                try(var s=db.prepareStatement("SELECT 1 FROM consumption_receipts WHERE town_id=? AND status='PREPARED'")){s.setLong(1,town.id());try(var r=s.executeQuery()){if(r.next())throw new IllegalArgumentException("Питание города ещё сохраняется. Повторите позже.");}}
            }
            case "spy_subvert_government" -> {
                if(wonder(db,town.civ(),"w_notre_dame"))throw new IllegalArgumentException("Нотр-Дам защищает цивилизацию от подрыва правительства.");
                building=nearest(db,town,at,false,null);if(building==null || !building.type().equals("s_capitol"))throw new IllegalArgumentException("Ближайшей постройкой должен быть капитолий.");
                try(var s=db.prepareStatement("SELECT government FROM civilizations WHERE id=?")){s.setLong(1,town.civ());try(var r=s.executeQuery()){if(!r.next() || java.util.Set.of("gov_anarchy","gov_tribalism").contains(r.getString(1)))throw new IllegalArgumentException("Племенной строй или анархию нельзя подорвать.");}}
            }
            case "spy_pirate" -> {
                building=nearest(db,town,at,true,null);if(building==null || !java.util.Set.of("ti_trade_outpost","ti_fishing_boat").contains(building.type()))throw new IllegalArgumentException("Ближайшей постройкой цивилизации должен быть торговый пост или рыбацкая лодка.");
                if(building.type().equals("ti_fishing_boat") && wonder(db,town.civ(),"w_grand_ship_ingermanland"))throw new IllegalArgumentException("Ингерманланд защищает рыбацкие лодки от пиратства.");
                var good=com.avrgaming.civcraft.modern.trade.BonusGoodieStore.origin(db,building.id());if(good==null || good.kind()!=com.avrgaming.civcraft.modern.trade.BonusGoodieStore.Kind.SOURCE)throw new IllegalArgumentException("В исходной рамке нет торгового товара.");
                com.avrgaming.civcraft.modern.trade.GoodieTransferStore.requireOriginUnlocked(db,building.id());
            }
            case "spy_sabotage" -> {
                building=nearest(db,town,at,false,null,true);if(building==null)throw new IllegalArgumentException("Постройка для саботажа не найдена.");
                if(java.util.Set.of("s_capitol","s_townhall").contains(building.type()))throw new IllegalArgumentException("Ратушу нельзя уничтожить саботажем.");
                if(building.type().startsWith("w_")){if(!java.util.Set.of("QUEUED","BUILDING").contains(building.status()))throw new IllegalArgumentException("Завершённое чудо нельзя уничтожить саботажем.");mission=catalog.require("spy_sabotage_wonder");}
                else if(!building.status().equals("COMPLETE"))throw new IllegalArgumentException("Саботаж требует завершённой неразрушенной постройки.");
                com.avrgaming.civcraft.modern.town.ProductionStore.requireBuildUnlocked(db,building.id());com.avrgaming.civcraft.modern.trade.TradeShipOperations.requireBuildUnlocked(db,building.id());
                if(com.avrgaming.civcraft.modern.build.BuildingActivityStore.pending(db,building.id()))throw new IllegalArgumentException("Постройка сохраняет операцию. Повторите позже.");
            }
            default -> throw new IllegalArgumentException("Эта миссия отключена в исходном режиме.");
        }
        double distance=building==null?0:building.distanceSquared(at);
        if(mission.id().equals("spy_pirate")) {
            var frame=com.avrgaming.civcraft.modern.trade.GoodieFrameStore.building(db,building.id()).stream().filter(f->f.source()).findFirst().orElseThrow(()->new IllegalArgumentException("Исходная рамка торгового поста недоступна."));
            double dx=frame.x()-1-at.getX(),dy=frame.y()-1-at.getY(),dz=frame.z()-at.getZ();distance=frame.world().equals(at.getWorld().getName())?dx*dx+dy*dy+dz*dz:Double.POSITIVE_INFINITY;
        }
        if(distance>mission.range()*mission.range())throw new IllegalArgumentException("Слишком далеко от цели. Допустимое расстояние: "+mission.range()+" блоков.");
        return new Target(town,building,mission);
    }
}
