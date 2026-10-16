package com.avrgaming.civcraft.modern.town;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public final class TaxService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    public TaxService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        double coinsPerBeaker=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"civcraft/data/civ.yml")).getDouble("civ.coins_per_beaker",10);
        storage.transaction(db->{TaxStore.install(db);com.avrgaming.civcraft.modern.research.CivilizationScienceStore.install(db,coinsPerBeaker);return null;});
        long hours=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder(),"civcraft/data/civ.yml")).getLong("global.join_civ_cooldown",12);
        if(hours<0 || hours>87600)throw new IllegalArgumentException("Invalid civilization rejoin cooldown");
        storage.setJoinCooldownMillis(hours*3600000);
    }
    public void settings(Player player,String[] args,boolean civilization)throws SQLException {
        if(civilization && args.length>=3 && args[2].equalsIgnoreCase("science")){science(player,args);return;}
        if(args.length<3 || args.length>4)throw new IllegalArgumentException(civilization?"/civ set taxes [процент]":"/town set flattax [монеты], /town set taxrate [процент]");
        String field= civilization && args[2].equalsIgnoreCase("taxes")?"income_tax_rate":
                !civilization && args[2].equalsIgnoreCase("flattax")?"flat_tax":
                !civilization && args[2].equalsIgnoreCase("taxrate")?"property_tax_rate":null;
        if(field==null)throw new IllegalArgumentException("Неизвестный параметр налога.");
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        Long id=civilization?resident.civId():resident.townId();
        if(id==null)throw new IllegalArgumentException("Вы не состоите в поселении.");
        String table=civilization?"civilizations":"towns";
        boolean percentage=!field.equals("flat_tax");
        double value;
        if(args.length==3)value=storage.query(db->{try(var s=db.prepareStatement("SELECT "+field+" FROM "+table+" WHERE id=?")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Missing tax account");return r.getDouble(1);}}});
        else {
            try{value=Double.parseDouble(args[3]);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Укажите числовую сумму или процент.");}
            if(!Double.isFinite(value) || value<0 || (percentage?value>100:value!=Math.rint(value) || value>=0x1p63))throw new IllegalArgumentException("Процент: от 0 до 100; сумма: целое неотрицательное число.");
            if(percentage)value/=100;
            final double requested=value;
            storage.transaction(db->{
                try(var s=db.prepareStatement("SELECT "+(civilization?"leader_uuid,government":"mayor_uuid")+" FROM "+table+" WHERE id=?")) {
                    s.setLong(1,id);try(var r=s.executeQuery()) {
                        if(!r.next() || !player.getUniqueId().toString().equals(r.getString(1)))throw new IllegalArgumentException("Налоги устанавливает руководитель поселения.");
                        if(civilization && requested>plugin.governments().maximumTax(r.getString(2)))throw new IllegalArgumentException("Превышен максимальный налог этого правительства.");
                    }
                }
                try(var s=db.prepareStatement("UPDATE "+table+" SET "+field+"=? WHERE id=?")){s.setDouble(1,requested);s.setLong(2,id);s.executeUpdate();}
                return null;
            });
        }
        String name=field.equals("flat_tax")?"Подушный налог":field.equals("property_tax_rate")?"Налог на участки":"Налог цивилизации";
        player.sendMessage(Component.text(name+": "+(percentage?value*100+"%":(long)value+" монет/день")));
    }
    private void science(Player player,String[] args)throws SQLException {
        if(args.length<3 || args.length>4)throw new IllegalArgumentException("/civ set science [процент]");
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        if(resident.civId()==null)throw new IllegalArgumentException("Вы не состоите в цивилизации.");
        Double requested=null;
        if(args.length==4){try{requested=Double.parseDouble(args[3].replace("%",""))/100;}catch(NumberFormatException invalid){throw new IllegalArgumentException("Укажите процент от 0 до 100.");}if(!Double.isFinite(requested) || requested<0 || requested>1)throw new IllegalArgumentException("Укажите процент от 0 до 100.");}
        final Double setting=requested;
        double value=storage.transaction(db->{
            com.avrgaming.civcraft.modern.diplomacy.DiplomacyStore.authority(db,resident.civId(),player.getUniqueId());
            if(setting!=null)com.avrgaming.civcraft.modern.research.CivilizationScienceStore.setPercentage(db,resident.civId(),setting);
            return com.avrgaming.civcraft.modern.research.CivilizationScienceStore.percentage(db,resident.civId());
        });
        player.sendMessage(Component.text("Научный бюджет: "+value*100+"% налоговых поступлений. Без исследования колбы идут на активную научную победу; иначе налоговые колбы не сохраняются."));
    }
    public void resident(Player player,boolean pay)throws SQLException {
        if(pay) {
            long paid=storage.transaction(db->TaxStore.payDebt(db,player.getUniqueId()));
            player.sendMessage(Component.text("Налоговый долг погашен: "+paid+" монет."));return;
        }
        String info=storage.query(db->{
            try(var s=db.prepareStatement("SELECT r.debt,r.days_til_evict,t.flat_tax,t.property_tax_rate,t.mayor_uuid,r.town_id,EXISTS(SELECT 1 FROM town_assistants a WHERE a.town_id=r.town_id AND a.player_uuid=r.uuid) FROM residents r LEFT JOIN towns t ON t.id=r.town_id WHERE r.uuid=?")) {
                s.setString(1,player.getUniqueId().toString());try(var r=s.executeQuery()) {
                    if(!r.next())throw new IllegalArgumentException("Профиль жителя не найден.");
                    if(r.getObject(6)==null)return "Вы не состоите в городе. Налоговый долг: "+r.getLong(1)+".";
                    boolean exempt=player.getUniqueId().toString().equals(r.getString(5)) || r.getBoolean(7);
                    return "Подушный налог: "+r.getLong(3)+". Налог на участки: "+r.getDouble(4)*100+"%. Долг: "+r.getLong(1)+". Расчётов до выселения: "+r.getInt(2)+(exempt?". Вы освобождены от налогов.":".");
                }
            }
        });
        player.sendMessage(Component.text(info));
    }
    public void notify(TaxStore.Notice notice) {
        var player=plugin.getServer().getPlayer(notice.player());
        if(player!=null)player.sendMessage(Component.text(notice.evicted()?"Вы выселены из города за налоговый долг. Участки возвращены городу.":"Налоговый долг: "+notice.debt()+". Расчётов до выселения: "+notice.days()+". Погасить: /res paydebt"));
    }
}
