package com.avrgaming.civcraft.modern.diplomacy;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.domain.CivRecord;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.sql.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

public final class DiplomacyService {
    private final CivCraftModernPlugin plugin;private final StorageBootstrap storage;
    private final com.avrgaming.civcraft.modern.war.RevolutionRules revolutionRules;
    public DiplomacyService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;storage.transaction(db->{DiplomacyStore.install(db);com.avrgaming.civcraft.modern.war.OccupationActionsStore.install(db);return null;});
        revolutionRules=new com.avrgaming.civcraft.modern.war.RevolutionRules(new java.io.File(plugin.getDataFolder(),"civcraft/data"));
    }
    public void revolution(Player player,String[] args)throws SQLException {
        boolean confirmed=args.length==3 && args[2].equalsIgnoreCase("yes");
        if(args.length>3 || (args.length==3 && !confirmed))throw new IllegalArgumentException("Использование: /civ revolution [yes]");
        var result=storage.transaction(db->{long now=System.currentTimeMillis();return confirmed?com.avrgaming.civcraft.modern.war.OccupationActionsStore.revolution(db,player.getUniqueId(),revolutionRules,plugin.warClock().calendar(),now):com.avrgaming.civcraft.modern.war.OccupationActionsStore.revolutionOffer(db,player.getUniqueId(),revolutionRules,plugin.warClock().calendar(),now);});
        if(!confirmed){player.sendMessage(Component.text("Восстание "+result.name()+" стоит "+result.fee().coins()+" монет из казны столицы. Будут возвращены города: "+result.fee().towns()+"; их владельцам будет объявлена война. Подтвердите: /civ revolution yes"));return;}
        plugin.governments().refreshQueuedProduction();plugin.getServer().broadcast(Component.text("Цивилизация "+result.name()+" восстановлена восстанием. Города возвращены, прежние владельцы объявлены агрессорами в войне."));
    }
    private CivRecord own(Player player)throws SQLException {
        var resident=storage.findResident(player.getUniqueId()).orElseThrow();
        if(resident.civId()==null)throw new IllegalArgumentException("Вы не состоите в цивилизации.");
        return storage.findCiv(resident.civId()).orElseThrow();
    }
    private CivRecord named(String name)throws SQLException {
        long id=storage.query(db->{try(var s=db.prepareStatement("SELECT id FROM civilizations WHERE name=? COLLATE NOCASE")) {
            s.setString(1,name);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Цивилизация не найдена: "+name);return r.getLong(1);}
        }});return storage.findCiv(id).orElseThrow(()->new IllegalArgumentException("Цивилизация побеждена и не участвует в дипломатии: "+name));
    }
    private void tell(long civ,String text)throws SQLException {
        for(var player:plugin.getServer().getOnlinePlayers()) {
            var resident=storage.findResident(player.getUniqueId()).orElse(null);
            if(resident!=null && resident.civId()!=null && resident.civId()==civ)player.sendMessage(Component.text(text));
        }
    }
    public void tick() {
        try{storage.transaction(db->{long now=System.currentTimeMillis();DiplomacyStore.expire(db,now);com.avrgaming.civcraft.modern.war.CapitulationStore.expire(db,now);return null;});}
        catch(SQLException failure){plugin.getLogger().warning("Diplomatic request expiration failed: "+failure.getMessage());}
    }
    public void advisors(Player player,String[] args)throws SQLException {
        var civ=own(player);
        if(args.length==2 || args[2].equalsIgnoreCase("list")) {
            var names=storage.query(db->{var out=new ArrayList<String>();try(var s=db.prepareStatement("SELECT r.name FROM civ_advisors a JOIN residents r ON r.uuid=a.resident_uuid AND r.civ_id=a.civ_id WHERE a.civ_id=? ORDER BY r.name")) {
                s.setLong(1,civ.id());try(var r=s.executeQuery()){while(r.next())out.add(r.getString(1));}
            }return out;});player.sendMessage(Component.text("Советники: "+(names.isEmpty()?"нет":String.join(", ",names))));return;
        }
        if(args.length!=4 || !List.of("add","remove").contains(args[2].toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Использование: /civ advisor add|remove <ник>");
        var target=storage.findResidentByName(args[3]).orElseThrow(()->new IllegalArgumentException("Игрок не найден."));
        boolean add=args[2].equalsIgnoreCase("add");storage.transaction(db->{DiplomacyStore.advisor(db,civ.id(),player.getUniqueId(),target.uuid(),add);return null;});
        tell(civ.id(),target.name()+(add?" назначен советником цивилизации.":" снят с должности советника."));
    }
    public void command(Player player,String[] args)throws SQLException {
        String action=args.length>2?args[2].toLowerCase(Locale.ROOT):"show";
        if(action.equals("capitulate")) {
            if(args.length<4)throw new IllegalArgumentException("Использование: /civ dip capitulate <город> [yes]");
            boolean confirmed=args.length>4 && args[args.length-1].equalsIgnoreCase("yes");String town=String.join(" ",Arrays.copyOfRange(args,3,confirmed?args.length-1:args.length));
            var offer=storage.transaction(db->{com.avrgaming.civcraft.modern.war.WarClockStore.requireDiplomacyOpen(db,plugin.warClock().calendar(),System.currentTimeMillis());return com.avrgaming.civcraft.modern.war.CapitulationStore.offer(db,town,player.getUniqueId());});
            if(!confirmed){player.sendMessage(Component.text(offer.whole()?"Капитуляция удалит исходную цивилизацию "+offer.motherName()+" и её право на возврат всех захваченных городов.":"Капитуляция снимет право исходной цивилизации на возврат города "+offer.townName()+"."));player.sendMessage(Component.text("Владелец должен принять предложение. Подтвердите: /civ dip capitulate "+town+" yes"));return;}
            var sent=storage.transaction(db->{long now=System.currentTimeMillis();com.avrgaming.civcraft.modern.war.WarClockStore.requireDiplomacyOpen(db,plugin.warClock().calendar(),now);return com.avrgaming.civcraft.modern.war.CapitulationStore.request(db,town,player.getUniqueId(),now);});
            tell(sent.offer().owner(),"Предложение капитуляции от "+sent.offer().motherName()+" ("+sent.offer().townName()+"). Ответьте /civ dip respond yes|no в течение 30 секунд.");player.sendMessage(Component.text("Предложение капитуляции отправлено владельцу."));return;
        }
        var civ=own(player);
        if(action.equals("liberate")) {
            if(args.length<4)throw new IllegalArgumentException("Использование: /civ dip liberate <город>");
            var result=storage.transaction(db->{long now=System.currentTimeMillis();com.avrgaming.civcraft.modern.war.WarClockStore.requireDiplomacyOpen(db,plugin.warClock().calendar(),now);return com.avrgaming.civcraft.modern.war.OccupationActionsStore.liberate(db,String.join(" ",Arrays.copyOfRange(args,3,args.length)),player.getUniqueId(),now);});
            plugin.governments().refreshQueuedProduction();plugin.getServer().broadcast(Component.text(result.civilization()?"Цивилизация "+result.mother()+" освобождена. Возвращено городов: "+result.towns()+".":"Город "+result.town()+" освобождён и возвращён в "+result.mother()+"."));return;
        }
        if(action.equals("calendar")){plugin.warClock().info(player);return;}
        if(action.equals("help")) {
            player.sendMessage(Component.text("/civ dip show [цивилизация], global, wars, calendar\n/civ dip request <цивилизация> ALLY|PEACE|NEUTRAL\n/civ dip respond yes|no\n/civ dip liberate <город>\n/civ dip capitulate <город> [yes]\n/civ dip declare <цивилизация> HOSTILE\n/civ advisor list|add|remove"));return;
        }
        if(List.of("show","global","wars").contains(action)) {
            var selected=action.equals("show") && args.length>3?named(String.join(" ",Arrays.copyOfRange(args,3,args.length))):civ;
            var rows=storage.query(db->{var out=new ArrayList<String>();String filter=action.equals("global")?"1=1":action.equals("wars")?"d.status='WAR'":"(d.first_civ=? OR d.second_civ=?)";
                try(var s=db.prepareStatement("SELECT a.name,b.name,d.status FROM diplomacy_relations d JOIN civilizations a ON a.id=d.first_civ JOIN civilizations b ON b.id=d.second_civ WHERE "+filter+" AND d.status!='NEUTRAL' ORDER BY a.name,b.name")) {
                    if(action.equals("show")){s.setLong(1,selected.id());s.setLong(2,selected.id());}
                    try(var r=s.executeQuery()){while(r.next())out.add(r.getString(1)+" — "+DiplomacyStore.Status.valueOf(r.getString(3)).title()+" — "+r.getString(2));}
                }return out;
            });player.sendMessage(Component.text(rows.isEmpty()?"Дипломатических отношений нет; действует нейтралитет.":String.join("\n",rows)));
            if(action.equals("show") && selected.id()==civ.id()) {
                var pending=storage.query(db->DiplomacyStore.incoming(db,civ.id(),System.currentTimeMillis()));
                if(pending!=null)player.sendMessage(Component.text("Предложение от "+storage.findCiv(pending.from()).orElseThrow().name()+": "+pending.status().title()+". Ответ: /civ dip respond yes|no"));
                var capitulation=storage.query(db->com.avrgaming.civcraft.modern.war.CapitulationStore.incoming(db,civ.id(),System.currentTimeMillis()));
                if(capitulation!=null)player.sendMessage(Component.text("Капитуляция от "+capitulation.offer().motherName()+": /civ dip respond yes|no"));
            }return;
        }
        if(action.equals("respond")) {
            if(args.length!=4 || !List.of("yes","no","да","нет").contains(args[3].toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Ответ: /civ dip respond yes|no");
            boolean accept=args[3].equalsIgnoreCase("yes") || args[3].equalsIgnoreCase("да");
            var capitulation=storage.query(db->com.avrgaming.civcraft.modern.war.CapitulationStore.incoming(db,civ.id(),System.currentTimeMillis()));
            if(capitulation!=null) {
                var answered=storage.transaction(db->{long now=System.currentTimeMillis();com.avrgaming.civcraft.modern.war.WarClockStore.requireDiplomacyOpen(db,plugin.warClock().calendar(),now);return com.avrgaming.civcraft.modern.war.CapitulationStore.respond(db,civ.id(),player.getUniqueId(),accept,now);});
                if(accept){plugin.governments().refreshQueuedProduction();plugin.getServer().broadcast(Component.text("Капитуляция "+answered.offer().motherName()+" принята. Города остаются у нынешних владельцев; статус захвата снят."));}else{tell(civ.id(),"Предложение капитуляции отклонено.");var sender=Bukkit.getPlayer(UUID.fromString(answered.actor()));if(sender!=null)sender.sendMessage(Component.text("Владелец отклонил капитуляцию."));}return;
            }
            var request=storage.transaction(db->{long now=System.currentTimeMillis();com.avrgaming.civcraft.modern.war.WarClockStore.requireDiplomacyOpen(db,plugin.warClock().calendar(),now);return DiplomacyStore.respond(db,civ.id(),player.getUniqueId(),accept,now);});
            String text=civ.name()+(accept?" приняла предложение: ":" отклонила предложение: ")+request.status().title()+".";
            tell(request.from(),text);tell(civ.id(),text);return;
        }
        if(args.length<5 || !List.of("request","declare").contains(action))throw new IllegalArgumentException("Команды дипломатии: /civ dip help");
        var other=named(String.join(" ",Arrays.copyOfRange(args,3,args.length-1)));
        DiplomacyStore.Status status;
        try{status=DiplomacyStore.Status.valueOf(args[args.length-1].toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Отношения: ALLY, PEACE, NEUTRAL или HOSTILE.");}
        if(action.equals("request")) {
            storage.transaction(db->{long now=System.currentTimeMillis();var calendar=plugin.warClock().calendar();com.avrgaming.civcraft.modern.war.WarClockStore.requireDiplomacyOpen(db,calendar,now);
                if(status==DiplomacyStore.Status.ALLY && calendar.declarationClosed(java.time.Instant.ofEpochMilli(now)) && (com.avrgaming.civcraft.modern.war.WarClockStore.hasWars(db,civ.id()) || com.avrgaming.civcraft.modern.war.WarClockStore.hasWars(db,other.id())))throw new IllegalArgumentException("Слишком близко к боевому периоду: воюющая цивилизация не может заключить новый союз.");
                return DiplomacyStore.request(db,civ.id(),other.id(),player.getUniqueId(),status,now);});
            tell(other.id(),civ.name()+" предлагает: "+status.title()+". Ответьте /civ dip respond yes|no в течение 30 секунд.");
            tell(civ.id(),"Предложение отправлено цивилизации "+other.name()+".");
        }else {
            if(status!=DiplomacyStore.Status.HOSTILE)throw new IllegalArgumentException("Сейчас доступно объявление HOSTILE. Объявление войны будет подключено вместе с военными правилами.");
            storage.transaction(db->{long now=System.currentTimeMillis();com.avrgaming.civcraft.modern.war.WarClockStore.requireDiplomacyOpen(db,plugin.warClock().calendar(),now);return DiplomacyStore.hostile(db,civ.id(),other.id(),player.getUniqueId(),now);});
            plugin.getServer().broadcast(Component.text(civ.name()+" объявила враждебность цивилизации "+other.name()+"."));
        }
    }
}
