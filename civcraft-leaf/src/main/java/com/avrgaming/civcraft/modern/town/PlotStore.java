package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

public final class PlotStore {
    private PlotStore() {}
    public record Key(String world,int x,int z) {}
    public record Plot(Key key,long town,long civ,String owner,boolean forSale,long price,long value,String groups) {}
    public static final Set<String> ACTIONS=Set.of("build","destroy","interact","itemuse");
    public static final Set<String> GROUPS=Set.of("default","mayors","assistants");
    public static void installClaims(Connection db)throws SQLException {
        column(db,"permit_mobs","INTEGER NOT NULL DEFAULT 0");
        column(db,"for_sale","INTEGER NOT NULL DEFAULT 0");
        column(db,"sale_price","INTEGER NOT NULL DEFAULT 0");
        column(db,"plot_groups","TEXT NOT NULL DEFAULT 'default'");
        for(var action:ACTIONS)column(db,"permit_"+action,"INTEGER NOT NULL DEFAULT 3");
    }
    public static void install(Connection db)throws SQLException {
        installClaims(db);
        try(var s=db.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS plot_sales(id INTEGER PRIMARY KEY AUTOINCREMENT,world TEXT NOT NULL,chunk_x INTEGER NOT NULL,chunk_z INTEGER NOT NULL,town_id INTEGER NOT NULL,buyer_uuid TEXT NOT NULL,seller_uuid TEXT,price INTEGER NOT NULL,purchased_at INTEGER NOT NULL)");}
    }
    private static void column(Connection db,String name,String type)throws SQLException {
        try(var s=db.createStatement()) {
            boolean found=false;try(var r=s.executeQuery("PRAGMA table_info(town_claims)")){while(r.next())if(name.equals(r.getString("name")))found=true;}
            if(!found)s.executeUpdate("ALTER TABLE town_claims ADD COLUMN "+name+" "+type);
        }
    }
    private static void key(PreparedStatement s,int start,Key key)throws SQLException {s.setString(start,key.world());s.setInt(start+1,key.x());s.setInt(start+2,key.z());}
    public static Plot find(Connection db,Key key)throws SQLException {
        try(var s=db.prepareStatement("SELECT town_id,civ_id,owner_uuid,for_sale,sale_price,purchase_value,plot_groups FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            key(s,1,key);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Этот чанк не принадлежит городу.");return new Plot(key,r.getLong(1),r.getLong(2),r.getString(3),r.getBoolean(4),r.getLong(5),r.getLong(6),r.getString(7));}
        }
    }
    private static void requireManager(Connection db,Plot plot,UUID actor)throws SQLException {
        if(plot.owner()!=null) {
            if(!actor.toString().equals(plot.owner()))throw new IllegalArgumentException("Управлять участком может только его владелец.");
            return;
        }
        try(var s=db.prepareStatement("""
            SELECT 1 FROM residents r JOIN towns t ON t.id=r.town_id JOIN civilizations c ON c.id=t.civ_id
            WHERE r.uuid=? AND t.id=? AND (t.mayor_uuid=r.uuid OR c.leader_uuid=r.uuid
            OR EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=r.uuid))
            """)) {
            s.setString(1,actor.toString());s.setLong(2,plot.town());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Участком города управляет мэр, помощник или лидер.");}
        }
    }
    public static boolean toggleMobs(Connection db,Key key,UUID actor)throws SQLException {
        var plot=find(db,key);requireManager(db,plot,actor);
        try(var s=db.prepareStatement("UPDATE town_claims SET permit_mobs=CASE WHEN permit_mobs=0 THEN 1 ELSE 0 END WHERE world=? AND chunk_x=? AND chunk_z=?")){key(s,1,key);s.executeUpdate();}
        return mobs(db,key);
    }
    public static boolean mobs(Connection db,Key key)throws SQLException {
        try(var s=db.prepareStatement("SELECT permit_mobs FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=?")){
            key(s,1,key);try(var r=s.executeQuery()){return !r.next() || r.getBoolean(1);}
        }
    }
    public static void offer(Connection db,Key key,UUID actor,Long price)throws SQLException {
        var plot=find(db,key);requireManager(db,plot,actor);
        if(price!=null && price<0)throw new IllegalArgumentException("Цена должна быть неотрицательной.");
        try(var s=db.prepareStatement("UPDATE town_claims SET for_sale=?,sale_price=? WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            s.setInt(1,price==null?0:1);s.setLong(2,price==null?plot.price():price);key(s,3,key);s.executeUpdate();
        }
    }
    public static long buy(Connection db,Key key,UUID buyer,long now)throws SQLException {
        var plot=find(db,key);
        if(!plot.forSale())throw new IllegalArgumentException("Участок не продаётся.");
        if(buyer.toString().equals(plot.owner()))throw new IllegalArgumentException("Вы уже владеете участком.");
        if(plot.price()<0)throw new SQLException("Invalid plot sale price");
        try(var s=db.prepareStatement("UPDATE residents SET coins=coins-? WHERE uuid=? AND town_id=? AND coins>=?")) {
            s.setLong(1,plot.price());s.setString(2,buyer.toString());s.setLong(3,plot.town());s.setLong(4,plot.price());
            if(s.executeUpdate()!=1)throw new IllegalArgumentException("Недостаточно монет или вы не житель этого города.");
        }
        if(plot.owner()==null) {
            try(var s=db.prepareStatement("UPDATE towns SET coins=coins+? WHERE id=? AND coins<=?")) {
                s.setLong(1,plot.price());s.setLong(2,plot.town());s.setLong(3,Long.MAX_VALUE-plot.price());if(s.executeUpdate()!=1)throw new SQLException("Plot town treasury overflow");
            }
        } else {
            try(var s=db.prepareStatement("UPDATE residents SET coins=coins+? WHERE uuid=? AND town_id=? AND coins<=?")) {
                s.setLong(1,plot.price());s.setString(2,plot.owner());s.setLong(3,plot.town());s.setLong(4,Long.MAX_VALUE-plot.price());
                if(s.executeUpdate()!=1)throw new SQLException("Plot seller missing or wallet overflow");
            }
        }
        try(var s=db.prepareStatement("UPDATE town_claims SET owner_uuid=?,purchase_value=?,for_sale=0,sale_price=0,plot_groups='' WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            s.setString(1,buyer.toString());s.setLong(2,plot.price());key(s,3,key);s.executeUpdate();
        }
        try(var s=db.prepareStatement("INSERT INTO plot_sales(world,chunk_x,chunk_z,town_id,buyer_uuid,seller_uuid,price,purchased_at) VALUES(?,?,?,?,?,?,?,?)")) {
            key(s,1,key);s.setLong(4,plot.town());s.setString(5,buyer.toString());s.setString(6,plot.owner());s.setLong(7,plot.price());s.setLong(8,now);s.executeUpdate();
        }
        return plot.price();
    }
    public static void owner(Connection db,Key key,UUID actor,UUID target)throws SQLException {
        var plot=find(db,key);requireManager(db,plot,actor);
        if(target!=null)try(var s=db.prepareStatement("SELECT 1 FROM residents WHERE uuid=? AND town_id=?")) {
            s.setString(1,target.toString());s.setLong(2,plot.town());try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Новый владелец должен быть жителем города.");}
        }
        try(var s=db.prepareStatement("UPDATE town_claims SET owner_uuid=? WHERE world=? AND chunk_x=? AND chunk_z=?")){s.setString(1,target==null?null:target.toString());key(s,2,key);s.executeUpdate();}
    }
    public static void group(Connection db,Key key,UUID actor,String group,boolean add)throws SQLException {
        var plot=find(db,key);requireManager(db,plot,actor);
        if(!GROUPS.contains(group))throw new IllegalArgumentException("Группы: default, mayors, assistants");
        var groups=new TreeSet<>(Arrays.asList(plot.groups().split(",")));groups.remove("");
        if(add)groups.add(group);else groups.remove(group);
        try(var s=db.prepareStatement("UPDATE town_claims SET plot_groups=? WHERE world=? AND chunk_x=? AND chunk_z=?")){s.setString(1,String.join(",",groups));key(s,2,key);s.executeUpdate();}
    }
    public static void permission(Connection db,Key key,UUID actor,String action,String audience,boolean allow)throws SQLException {
        var plot=find(db,key);requireManager(db,plot,actor);validateAction(action);
        int flag=switch(audience){case "owner"->1;case "group"->2;case "others"->4;default->throw new IllegalArgumentException("Права: owner, group, others");};
        try(var s=db.prepareStatement("UPDATE town_claims SET permit_"+action+"=(permit_"+action+(allow?" | ?":" & ?")+") WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            s.setInt(1,allow?flag:7^flag);key(s,2,key);s.executeUpdate();
        }
    }
    private static void validateAction(String action){if(!ACTIONS.contains(action))throw new IllegalArgumentException("Действия: build, destroy, interact, itemuse");}
    public static boolean allowed(Connection db,Key key,UUID actor,String action)throws SQLException {
        validateAction(action);var plot=find(db,key);int mask;
        try(var s=db.prepareStatement("SELECT permit_"+action+" FROM town_claims WHERE world=? AND chunk_x=? AND chunk_z=?")){key(s,1,key);try(var r=s.executeQuery()){if(!r.next())return false;mask=r.getInt(1);}}
        if(mask<0 || mask>7)throw new SQLException("Invalid plot permission mask");
        if((mask&4)!=0 || ((mask&1)!=0 && actor.toString().equals(plot.owner())))return true;
        if((mask&2)==0)return false;
        var groups=new HashSet<>(Arrays.asList(plot.groups().split(",")));
        try(var s=db.prepareStatement("SELECT t.mayor_uuid,EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=t.id AND a.player_uuid=r.uuid) FROM residents r JOIN towns t ON t.id=r.town_id WHERE r.uuid=? AND t.id=?")) {
            s.setString(1,actor.toString());s.setLong(2,plot.town());try(var r=s.executeQuery()) {
                return r.next() && (groups.contains("default") || (groups.contains("mayors") && actor.toString().equals(r.getString(1))) || (groups.contains("assistants") && r.getBoolean(2)));
            }
        }
    }
}
