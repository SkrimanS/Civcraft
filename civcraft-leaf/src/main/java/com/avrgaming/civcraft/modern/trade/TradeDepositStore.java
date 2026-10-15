package com.avrgaming.civcraft.modern.trade;

import java.sql.*;

public final class TradeDepositStore {
    private TradeDepositStore() {}
    public record Deposit(long id,String good,String world,int x,int y,int z,Long build) {}
    public static boolean outpost(String type){return "ti_trade_outpost".equals(type)||"ti_fishing_boat".equals(type);}
    public static void install(Connection db)throws SQLException {
        try(var s=db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_deposits(id INTEGER PRIMARY KEY AUTOINCREMENT,good_id TEXT NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,build_id INTEGER UNIQUE REFERENCES structure_builds(id) ON DELETE SET NULL,UNIQUE(world,x,y,z))");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS trade_deposit_coordinates ON trade_deposits(world,x,z,y)");
            s.executeUpdate("CREATE TRIGGER IF NOT EXISTS release_demolished_trade_deposit AFTER UPDATE OF status ON structure_builds WHEN NEW.status IN ('DEMOLISHED','CANCELLED') BEGIN UPDATE trade_deposits SET build_id=NULL WHERE build_id=NEW.id; END");
        }
    }
    public static void register(Connection db,TradeGoodsCatalog catalog,String good,String world,int x,int y,int z)throws SQLException {
        catalog.require(good);if(world==null || world.isBlank())throw new IllegalArgumentException("Missing trade good world");
        try(var s=db.prepareStatement("INSERT INTO trade_deposits(good_id,world,x,y,z) VALUES(?,?,?,?,?)")) {
            s.setString(1,good);s.setString(2,world);s.setInt(3,x);s.setInt(4,y);s.setInt(5,z);s.executeUpdate();
        }
    }
    public static Deposit require(Connection db,TradeGoodsCatalog catalog,String type,String world,int x,int y,int z,int width,int height,int depth)throws SQLException {
        if(!outpost(type))throw new IllegalArgumentException("Not a trade outpost");
        if(width<1||height<1||depth<1)throw new IllegalArgumentException("Invalid trade outpost dimensions");
        Deposit deposit;
        // Original x/y/z scan keeps the last marker, and requires y+3 < regionY.
        try(var s=db.prepareStatement("SELECT * FROM trade_deposits WHERE world=? AND x>=? AND x<? AND y>=? AND y<=? AND z>=? AND z<? ORDER BY x DESC,y DESC,z DESC LIMIT 1")) {
            s.setString(1,world);s.setInt(2,x);s.setLong(3,(long)x+width);s.setInt(4,y);s.setLong(5,(long)y+height-4);s.setInt(6,z);s.setLong(7,(long)z+depth);
            try(var r=s.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("Постройка должна полностью накрывать месторождение торгового товара.");
                deposit=new Deposit(r.getLong("id"),r.getString("good_id"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getObject("build_id")==null?null:r.getLong("build_id"));
            }
        }
        boolean water=catalog.require(deposit.good()).water();
        if(water!=type.equals("ti_fishing_boat"))throw new IllegalArgumentException(water?"На водном месторождении нужна торговая лодка.":"Торговая лодка строится только на водном месторождении.");
        if(deposit.build()!=null)throw new IllegalArgumentException("Это месторождение уже занято торговым постом или лодкой.");
        return deposit;
    }
    public static void claim(Connection db,Deposit deposit,long build)throws SQLException {
        if(db.getAutoCommit())throw new SQLException("Trade deposit reservation requires a transaction");
        try(var s=db.prepareStatement("UPDATE trade_deposits SET build_id=? WHERE id=? AND build_id IS NULL")) {
            s.setLong(1,build);s.setLong(2,deposit.id());if(s.executeUpdate()!=1)throw new IllegalArgumentException("Месторождение уже занято другой постройкой.");
        }
    }
}
