package com.avrgaming.civcraft.modern.town;

import java.sql.*;
import java.util.*;

public final class BankDailyStore {
    private BankDailyStore() {}
    public static void install(Connection db)throws SQLException {
        try(var statement=db.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_economy_events(
                    event TEXT NOT NULL,day TEXT NOT NULL,processed_at INTEGER NOT NULL,
                    PRIMARY KEY(event,day))
                    """);
        }
    }
    /** Called in one transaction: the day marker and all payouts commit together. */
    public static Map<Long,Long> interest(Connection db,String day,long now)throws SQLException {
        try(var insert=db.prepareStatement("INSERT OR IGNORE INTO daily_economy_events VALUES('bank_interest',?,?)")) {
            insert.setString(1,day);insert.setLong(2,now);if(insert.executeUpdate()==0)return Map.of();
        }
        record Account(long town,long principal,long coins,double rate,boolean active) {}
        List<Account> accounts=new ArrayList<>();
        try(var rows=db.createStatement().executeQuery("""
                SELECT a.town_id,a.principal,t.coins,a.interest_rate,
                EXISTS(SELECT 1 FROM structure_builds sb WHERE sb.town_id=t.id AND sb.structure_id='s_bank' AND sb.status='COMPLETE')
                FROM bank_interest_accounts a JOIN towns t ON t.id=a.town_id
                """+" WHERE "+com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection.visible(db,"towns","t.id"))) {
            while(rows.next())accounts.add(new Account(rows.getLong(1),rows.getLong(2),rows.getLong(3),rows.getDouble(4),rows.getBoolean(5)));
        }
        Map<Long,Long> payments=new LinkedHashMap<>();
        for(var account:accounts) {
            long paid=account.active()?(long)Math.floor(Math.max(0,Math.min(account.principal(),account.coins()))*account.rate()):0;
            if(paid<0 || account.coins()>Long.MAX_VALUE-paid)throw new SQLException("Bank interest exceeds supported balance");
            if(paid>0)try(var update=db.prepareStatement("UPDATE towns SET coins=coins+? WHERE id=?")) {
                update.setLong(1,paid);update.setLong(2,account.town());update.executeUpdate();payments.put(account.town(),paid);
            }
            try(var update=db.prepareStatement("UPDATE bank_interest_accounts SET principal=? WHERE town_id=?")) {
                update.setLong(1,account.active()?account.coins()+paid:0);update.setLong(2,account.town());update.executeUpdate();
            }
        }
        return payments;
    }
}
