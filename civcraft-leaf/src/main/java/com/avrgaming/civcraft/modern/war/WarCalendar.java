package com.avrgaming.civcraft.modern.war;
import java.io.File;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import org.bukkit.configuration.file.YamlConfiguration;
/** WarEvent uses a weekly local calendar; War.setWarTime adds time_length MINUTES. */
public final class WarCalendar {
    public record Window(Instant start,Instant end) {
        public boolean contains(Instant now){return !now.isBefore(start) && now.isBefore(end);}
    }
    private final DayOfWeek day;private final int hour,minutes,declareDays,allyHours;private final ZoneId zone;
    public WarCalendar(File file,ZoneId zone) {
        this(YamlConfiguration.loadConfiguration(file),zone);
    }
    private WarCalendar(YamlConfiguration yaml,ZoneId zone) {
        this(yaml.getInt("war.time_day",7),yaml.getInt("war.time_hour",10),yaml.getInt("war.time_length",120),yaml.getInt("war.time_declare_days",3),yaml.getInt("war.ally_declare_hours",24),zone);
    }
    public WarCalendar(int calendarDay,int hour,int minutes,int declareDays,int allyHours,ZoneId zone) {
        if(calendarDay<1 || calendarDay>7 || hour<0 || hour>23 || minutes<1 || minutes>10080 || declareDays<0 || declareDays>7 || allyHours<0 || allyHours>168)
            throw new IllegalArgumentException("Invalid war calendar");
        this.day=DayOfWeek.of((calendarDay+5)%7+1);this.hour=hour;this.minutes=minutes;this.declareDays=declareDays;this.allyHours=allyHours;this.zone=zone;
    }
    public ZoneId zone(){return zone;}
    public Window current(Instant now) {
        var date=now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(day));
        var start=date.atTime(hour,0).atZone(zone);
        if(start.toInstant().isAfter(now))start=start.minusWeeks(1);
        return new Window(start.toInstant(),start.plusMinutes(minutes).toInstant());
    }
    public Instant next(Instant now) {
        var window=current(now);return window.start().atZone(zone).plusWeeks(1).toInstant();
    }
    public boolean declarationClosed(Instant now){return Duration.between(now,next(now)).compareTo(Duration.ofDays(declareDays))<=0;}
    public boolean allyDeclarationClosed(Instant now){return Duration.between(now,next(now)).compareTo(Duration.ofHours(allyHours))<0;}
}
