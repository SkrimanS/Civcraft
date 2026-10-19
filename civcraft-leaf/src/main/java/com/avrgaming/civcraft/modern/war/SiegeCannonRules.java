package com.avrgaming.civcraft.modern.war;

/** Direct source aiming factors, not trigonometric degrees or the tower trajectory. */
public final class SiegeCannonRules {
    private SiegeCannonRules() {}
    public record Vector(double x,double y,double z) {
        public Vector{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Неверный вектор пушки");}
        public Vector unit(){double length=Math.hypot(Math.hypot(x,y),z);if(length==0)throw new IllegalArgumentException("Нулевое направление пушки");return new Vector(x/length,y/length,z/length);}
    }
    public record Step(Vector position,Vector direction) {}
    public record Origin(int x,int y,int z,String direction) {}
    public static int yaw(int current,boolean right){if(current<-35||current>35)throw new IllegalArgumentException("Неверный угол поворота пушки");return Math.max(-35,Math.min(35,current+(right?1:-1)));}
    public static int pitch(int current,boolean right){if(current<0||current>50)throw new IllegalArgumentException("Неверный угол подъёма пушки");return Math.max(0,Math.min(50,current+(right?1:-1)));}
    public static Vector direction(int facing,int yaw,int pitch) {
        if(yaw<-35 || yaw>35 || pitch<0 || pitch>50)throw new IllegalArgumentException("Неверное прицеливание пушки");
        double y=pitch/100.0,a=yaw/100.0;
        return switch(facing){case 5->new Vector(1,y,a);case 4->new Vector(-1,y,-a);case 2->new Vector(a,y,-1);case 3->new Vector(-a,y,1);default->throw new IllegalArgumentException("Неверное направление дула пушки");};
    }
    public static Step advance(Vector position,Vector direction) {
        var unit=canonicalDirection(direction);var movement=new Vector(unit.x(),unit.y()-0.008,unit.z());
        return new Step(new Vector(position.x()+movement.x(),position.y()+movement.y(),position.z()+movement.z()),canonicalDirection(movement));
    }
    /** Location stores float yaw/pitch between steps in the original projectile. */
    public static Vector canonicalDirection(Vector direction) {
        direction.unit();
        var location=new org.bukkit.Location(null,0,0,0);location.setDirection(new org.bukkit.util.Vector(direction.x(),direction.y(),direction.z()));
        var result=location.getDirection();return new Vector(result.getX(),result.getY(),result.getZ());
    }
    public static boolean beyondRange(Vector start,Vector current,double maximum) {
        if(!Double.isFinite(maximum)||maximum<=0)throw new IllegalArgumentException("Неверная дальность пушки");
        return Math.hypot(Math.hypot(start.x()-current.x(),start.y()-current.y()),start.z()-current.z())>maximum;
    }
    public static Origin origin(double x,double y,double z,float yaw,int width,int depth) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||width<1||depth<1||width>256||depth>256)throw new IllegalArgumentException("Неверная исходная точка пушки");
        String direction=WarCampPlacementRules.direction(yaw);double ox=x,oz=z;
        switch(direction){case "east"->oz-=depth/2.0;case "west"->{ox-=width;oz-=depth/2.0;}case "north"->{ox-=width/2.0;oz-=depth;}case "south"->ox-=width/2.0;default->throw new IllegalArgumentException("Неверное направление пушки");}
        return new Origin(floor(ox),floor(y),floor(oz),direction);
    }
    private static int floor(double value){double floor=Math.floor(value);if(floor<Integer.MIN_VALUE || floor>Integer.MAX_VALUE)throw new IllegalArgumentException("Координата пушки за пределами мира");return (int)floor;}
    public static void height(double playerY,int originY,int height,int minimum,int maximum) {
        if(!Double.isFinite(playerY)||playerY>=128)throw new IllegalArgumentException("Пушку нельзя разместить на высоте 128 и выше.");
        if(originY<minimum || height<1 || (long)originY+height>=Math.min(255,maximum))throw new IllegalArgumentException("Пушка выходит за допустимую высоту мира.");
    }
    public static void terrain(int originY,double average) {
        if(!Double.isFinite(average)||Math.abs(originY-average)>10)throw new IllegalArgumentException("Пушка должна быть не дальше 10 блоков от средней поверхности.");
    }
}
