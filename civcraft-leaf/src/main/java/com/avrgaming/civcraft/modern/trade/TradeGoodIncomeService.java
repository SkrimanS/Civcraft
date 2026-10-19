package com.avrgaming.civcraft.modern.trade;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import com.avrgaming.civcraft.modern.town.HappinessRules;
import java.io.File;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TradeGoodIncomeService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final TradeGoodIncome income;
    private final HappinessRules happiness;
    private final long period;
    public TradeGoodIncomeService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;var folder=new File(plugin.getDataFolder(),"civcraft/data");
        income=new TradeGoodIncome(folder);happiness=new HappinessRules(new File(folder,"happiness.yml"));
        long seconds=YamlConfiguration.loadConfiguration(new File(folder,"civ.yml")).getLong("global.hourly_tick",3600);
        if(seconds<1||seconds>86400)throw new IllegalArgumentException("Неверный старый час торговли");
        period=Long.getLong("civcraft.test.goodieIncome.hourMillis",seconds*1000);
        if(period<1000||period>86400000)throw new IllegalArgumentException("Неверный период дохода торговых товаров");
        storage.transaction(db->{TradeGoodIncomeStore.install(db);TradeGoodIncomeStore.start(db,period,System.currentTimeMillis()/period);return null;});
    }
    public void tick() {
        try {
            var paid=storage.transaction(db->{
                long now=System.currentTimeMillis(),hour=now/period;
                if(!TradeGoodIncomeStore.boundary(db,period,hour,now))return Map.<Long,TradeShipIncome.Payment>of();
                var towns=new LinkedHashMap<Long,Long>();
                try(var s=db.createStatement();var r=s.executeQuery("SELECT DISTINCT t.id,t.civ_id FROM towns t JOIN bonus_goodies g ON g.host_town=t.id WHERE g.holder_kind='FRAME'")){while(r.next())towns.put(r.getLong(1),r.getLong(2));}
                var result=new LinkedHashMap<Long,TradeShipIncome.Payment>();
                for(var entry:towns.entrySet()) {
                    long townId=entry.getKey(),civ=entry.getValue();var payment=payment(db,townId,civ);
                    if(payment.town().signum()==0&&payment.civilization().signum()==0)continue;
                    if(TradeGoodIncomeStore.credit(db,period,hour,townId,civ,payment))result.put(townId,payment);
                }
                return result;
            });
            for(var player:plugin.getServer().getOnlinePlayers()) {
                var resident=storage.findResident(player.getUniqueId()).orElse(null);
                if(resident!=null&&resident.townId()!=null&&paid.containsKey(resident.townId())) {
                    var payment=paid.get(resident.townId());
                    player.sendMessage(Component.text("Доход торговых товаров: "+payment.town().add(payment.civilization()).stripTrailingZeros().toPlainString()+" монет. Налог цивилизации: "+payment.civilization().stripTrailingZeros().toPlainString()+"."));
                }
            }
        }catch(SQLException|RuntimeException failure){plugin.getLogger().warning("Trade good income failed: "+failure.getMessage());}
    }
    private TradeShipIncome.Payment payment(Connection db,long townId,long civ)throws SQLException {
        var town=storage.findTown(townId).orElseThrow();var goodies=storage.townGoodieEffects(townId);
        var base=income.base(TradeGoodIncomeStore.payable(db,townId),goodies.goods());
        double tax;try(var s=db.prepareStatement("SELECT income_tax_rate FROM civilizations WHERE id=?")){s.setLong(1,civ);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("Trade good civilization missing");tax=r.getDouble(1);}}
        return TradeGoodIncome.payment(base,plugin.governments().tradeRate(plugin.governments().townGovernment(townId)),
            TradeGoodIncomeStore.structureRate(db,townId,income),goodies.value("buff_monopoly"),happiness.evaluate(town.happiness(),town.unhappiness()).state().coins(),tax);
    }
    public void info(org.bukkit.entity.Player player)throws SQLException {
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();if(resident.townId()==null)throw new IllegalArgumentException("Вы не состоите в городе.");
        String text=storage.query(db->{
            long town=resident.townId(),civ=storage.findTown(town).orElseThrow().civId();var goodies=storage.townGoodieEffects(town);
            var names=goodies.goods().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->income.name(e.getKey())+" ×"+e.getValue()).toList();
            var payment=payment(db,town,civ);boolean war=com.avrgaming.civcraft.modern.war.WarClockStore.active(db,System.currentTimeMillis());
            return "Товары в ратуше: "+(names.isEmpty()?"нет":String.join(", ",names))+". Действующих источников: "+TradeGoodIncomeStore.payable(db,town).size()+
                ". Доход за час: "+payment.town().add(payment.civilization()).stripTrailingZeros().toPlainString()+" монет; городу: "+payment.town().stripTrailingZeros().toPlainString()+
                "; налог цивилизации: "+payment.civilization().stripTrailingZeros().toPlainString()+"."+(war?" Во время войны выплаты приостановлены.":"");
        });
        player.sendMessage(Component.text(text));
    }
}
