package com.avrgaming.civcraft.modern.endgame;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import com.avrgaming.civcraft.modern.storage.StorageBootstrap;
import java.io.File;
import java.sql.*;
import java.time.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class EndGameService {
    private final CivCraftModernPlugin plugin;
    private final StorageBootstrap storage;
    private final EndGameRules rules;
    private final ZoneId zone;
    private final int hour;
    public EndGameService(CivCraftModernPlugin plugin,StorageBootstrap storage)throws SQLException {
        this.plugin=plugin;this.storage=storage;
        var file=new File(plugin.getDataFolder(),"civcraft/data/civ.yml");
        rules=new EndGameRules(file,new File(plugin.getDataFolder(),"civcraft/data/culture.yml"));
        zone=ZoneId.of(plugin.getConfig().getString("server.timezone",ZoneId.systemDefault().getId()));
        hour=Integer.getInteger("civcraft.test.daily.hour",YamlConfiguration.loadConfiguration(file).getInt("global.daily_upkeep_hour",20));
        if(hour<0 || hour>23)throw new IllegalArgumentException("Неверный час проверки победы");
        storage.transaction(db->{EndGameStore.install(db,System.currentTimeMillis(),rules.scienceTech());return null;});
        plugin.getServer().getScheduler().runTaskTimer(plugin,this::notifyProgress,20L*3600,20L*3600);
    }
    private void notifyProgress() {
        if(plugin.warClock().casual())return;
        try {
            var winner=storage.query(EndGameStore::winner);
            if(winner!=null){plugin.getServer().broadcast(Component.text(EndGameStore.announcement(winner)));return;}
            for(var p:storage.query(EndGameStore::progress))plugin.getServer().broadcast(Component.text(p.name()+": "+EndGameRules.name(p.condition())+" победа — осталось "+Math.max(0,rules.condition(p.condition()).days()-p.held())+" дн. удержания условий."));
        }catch(SQLException failure){plugin.getLogger().warning("Victory notification failed: "+failure.getMessage());}
    }
    /** Called after the daily economy so today's wonder income and taxes are included. */
    public void daily() {
        var now=ZonedDateTime.now(zone);if(now.getHour()<hour)return;
        try {
            var messages=storage.transaction(db->EndGameStore.daily(db,now.toLocalDate().toString(),System.currentTimeMillis(),zone,rules,plugin.warClock().casual()));
            for(String message:messages)plugin.getServer().broadcast(Component.text(message));
        }catch(SQLException failure){plugin.getLogger().warning("Victory check failed: "+failure.getMessage());}
    }
    public void command(Player player,String[] args)throws SQLException {
        if(args.length>=2 && args[1].equalsIgnoreCase("top5")) {
            var lines=storage.query(db->{
                var out=new ArrayList<String>();boolean finished=EndGameStore.winner(db)!=null;
                String query=finished?"SELECT name,points FROM endgame_final_scores WHERE ranked=1 ORDER BY points DESC,civ_id LIMIT 5":"SELECT c.name,s.points FROM legacy_civ_scores s JOIN civilizations c ON c.id=s.civ_id WHERE s.ranked=1 ORDER BY s.points DESC,s.civ_id LIMIT 5";
                try(var s=db.createStatement();var r=s.executeQuery(query)){int rank=1;while(r.next())out.add((rank++)+") "+r.getString(1)+" — "+r.getLong(2)+" очков");}return out;
            });
            player.sendMessage(Component.text("Пять ведущих цивилизаций:"));for(String line:lines)player.sendMessage(Component.text(line));return;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("votes")) {
            var lines=storage.query(db->{var out=new ArrayList<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT c.name,v.votes FROM diplomatic_votes v JOIN civilizations c ON c.id=v.civ_id WHERE v.votes>0 AND NOT EXISTS(SELECT 1 FROM civ_conquests cc WHERE cc.civ_id=c.id) ORDER BY v.votes DESC,c.id")){while(r.next())out.add(r.getString(1)+": "+r.getLong(2)+" голосов");}return out;});
            player.sendMessage(Component.text(lines.isEmpty()?"Голосов пока нет.":"Голоса за дипломатическую победу:"));for(String line:lines)player.sendMessage(Component.text(line));return;
        }
        if(args.length>=2 && args[1].equalsIgnoreCase("vote")) {
            if(args.length<3)throw new IllegalArgumentException("/civ vote <цивилизация>");
            String name=String.join(" ",Arrays.copyOfRange(args,2,args.length));
            String target=storage.transaction(db->{
                long civ;String actual;try(var s=db.prepareStatement("SELECT id,name FROM civilizations WHERE name=? COLLATE NOCASE")){s.setString(1,name);try(var r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("Цивилизация не найдена.");civ=r.getLong(1);actual=r.getString(2);}}
                EndGameStore.vote(db,player.getUniqueId(),civ,System.currentTimeMillis(),rules.voteCooldownHours()*3600000L);return actual;
            });
            player.sendMessage(Component.text("Ваш голос отдан за цивилизацию "+target+". Повторное голосование — через "+rules.voteCooldownHours()+" ч."));return;
        }
        var winner=storage.query(EndGameStore::winner);
        if(winner!=null)player.sendMessage(Component.text(EndGameStore.announcement(winner)));
        if(plugin.warClock().casual())player.sendMessage(Component.text("В мирном режиме победитель раунда не определяется."));
        player.sendMessage(Component.text("Условия победы проверяются ежедневно в "+hour+":00 ("+zone+")."));
        for(var condition:rules.conditions()) {
            String detail=switch(condition.id()) {
                case "end_culture"->condition.integer("towns")+" незахваченных города с культурой уровня "+condition.integer("culture_level")+" и чудес: "+condition.integer("wonders");
                case "end_conquest"->"завоевать "+Math.round(condition.decimal("percent_captured")*100)+"% цивилизаций, с Чичен-Ицей — "+Math.round(condition.decimal("percent_captured_with_wonder")*100)+"%, после "+condition.integer("days_after_start")+" дн. раунда";
                case "end_science"->"изучить "+plugin.research().findTech(rules.scienceTech()).map(com.avrgaming.civcraft.modern.research.TechDefinition::name).orElse(rules.scienceTech())+", иметь Великую библиотеку в незахваченном городе и лидировать по накопленным колбам";
                case "end_diplomacy"->"Совет Восьми в незахваченном городе и наибольшее число голосов; /civ vote <цивилизация>";
                default->condition.id();
            };
            player.sendMessage(Component.text(EndGameRules.name(condition.id())+": "+detail+". Удержание: "+condition.days()+" дн."));
        }
        var progress=storage.query(EndGameStore::progress);
        if(progress.isEmpty())player.sendMessage(Component.text("Активных отсчётов победы нет."));
        for(var p:progress)player.sendMessage(Component.text(p.name()+" — "+EndGameRules.name(p.condition())+": "+p.held()+"/"+rules.condition(p.condition()).days()+" дн."));
        var totals=storage.query(db->{var out=new ArrayList<String>();try(var s=db.createStatement();var r=s.executeQuery("SELECT c.name,COALESCE(v.votes,0),COALESCE(sc.extra_beakers,0) FROM civilizations c LEFT JOIN diplomatic_votes v ON v.civ_id=c.id LEFT JOIN civilization_science sc ON sc.civ_id=c.id WHERE NOT EXISTS(SELECT 1 FROM civ_conquests cc WHERE cc.civ_id=c.id) ORDER BY c.name")){while(r.next())out.add(r.getString(1)+": голосов — "+r.getLong(2)+", накоплено колб — "+String.format(Locale.ROOT,"%.1f",r.getDouble(3)));}return out;});
        for(String total:totals)player.sendMessage(Component.text(total));
    }
}
