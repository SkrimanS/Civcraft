package com.avrgaming.civcraft.modern.road;

import java.util.*;

/** Original half-block route stepping, radius-three filled circles and segment accounting. */
public final class RoadRules {
    private RoadRules() {}
    public record Point(int x,int y,int z) {}
    public record Route(List<Point> floor,int segments){}
    public record Raid(boolean open,long next){}
    public static Route route(Point first,Point second,int maximum,int minimum,int ceiling){
        double dx=second.x()-first.x(),dy=second.y()-first.y(),dz=second.z()-first.z(),distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(distance>maximum)throw new IllegalArgumentException("Маркеры дороги должны быть не дальше "+maximum+" блоков друг от друга.");
        if(distance==0)throw new IllegalArgumentException("Маркеры дороги должны находиться в разных точках.");
        dx=dx/distance*.5;dy=dy/distance*.5;dz=dz/distance*.5;
        double x=first.x(),y=first.y()-1,z=first.z();int targetY=second.y()-1,lastY=(int)y,segments=1,steps=0;Point last=new Point((int)x,(int)y,(int)z);
        var floor=new LinkedHashSet<Point>();circle(last,floor);
        while(Math.sqrt(Math.pow(second.x()-x,2)+Math.pow(targetY-y,2)+Math.pow(second.z()-z,2))>1){
            x+=dx;y+=dy;z+=dz;var current=new Point((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
            if(Math.sqrt(Math.pow(current.x()-last.x(),2)+Math.pow(current.y()-last.y(),2)+Math.pow(current.z()-last.z(),2))<3)continue;
            last=current;if(Math.abs(lastY-current.y())>1)throw new IllegalArgumentException("Слишком крутой уклон дороги: не более одного блока на шаг маршрута.");
            if(current.y()<5)throw new IllegalArgumentException("Дорога проходит ниже высоты 5.");lastY=current.y();
            if(++steps>350)throw new IllegalArgumentException("Превышен предел шагов маршрута дороги.");circle(current,floor);segments++;
            if(Math.sqrt(Math.pow(second.x()-x,2)+Math.pow(targetY-y,2)+Math.pow(second.z()-z,2))>distance)break;
        }
        circle(new Point(second.x(),targetY,second.z()),floor);
        for(var p:floor)if(p.y()<minimum || (long)p.y()+3>=ceiling)throw new IllegalArgumentException("Дорога выходит за допустимую высоту мира.");
        return new Route(List.copyOf(floor),segments);
    }
    private static void circle(Point center,Set<Point> points){
        int error=-3,x=3,z=0;while(x>=z){int last=z;error+=z;++z;error+=z;four(center,x,last,points);if(error>=0){if(x!=last)four(center,last,x,points);error-=x;--x;error-=x;}}
    }
    private static void four(Point center,int x,int z,Set<Point> points){
        for(int i=center.x()-x;i<=center.x()+x;i++)points.add(new Point(i,center.y(),center.z()+z));
        if(x!=0 && z!=0)for(int i=center.x()-x;i<=center.x()+x;i++)points.add(new Point(i,center.y(),center.z()-z));
    }
    public static Raid raid(long builtAt,int hours,long now){
        long start=Math.addExact(builtAt,86400000L),length=Math.multiplyExact(hours,3600000L);
        if(now<=start)return new Raid(false,start);
        long day=Math.max(0,(now-start)/86400000L),current=start+day*86400000L;
        if(now==current && day>0 && length==86400000L)return new Raid(true,current);
        if(now>current && now<=current+length)return new Raid(true,current);
        return new Raid(false,now<=current?current:current+86400000L);
    }
    public static long cost(int segments,double price){double value=segments*price;if(!Double.isFinite(value) || value<0 || value>Long.MAX_VALUE)throw new IllegalArgumentException("Некорректная стоимость дороги.");return Math.round(value);}
}
