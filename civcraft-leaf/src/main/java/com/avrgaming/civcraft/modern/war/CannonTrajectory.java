package com.avrgaming.civcraft.modern.war;
public final class CannonTrajectory {
    private CannonTrajectory() {}
    public record Point(double x,double y,double z) {public double distanceSquared(Point other){return Math.pow(x-other.x,2)+Math.pow(y-other.y,2)+Math.pow(z-other.z,2);}}
    public record Step(Point position,boolean arrived) {}
    public static Step advance(Point from,Point target,int speed) {
        if(speed<1)throw new IllegalArgumentException("Cannon speed must be positive");double squared=from.distanceSquared(target);if(!Double.isFinite(squared))throw new IllegalArgumentException("Invalid cannon coordinates");
        if(squared<=speed*(double)speed)return new Step(target,true);double factor=speed/Math.sqrt(squared);var next=new Point(from.x+(target.x-from.x)*factor,from.y+(target.y-from.y)*factor,from.z+(target.z-from.z)*factor);
        return next.distanceSquared(target)<speed*1.5?new Step(target,true):new Step(next,false);
    }
    public static boolean splash(Point player,Point target,int configuredRadius) {
        var blockPlayer=new Point(Math.floor(player.x),Math.floor(player.y),Math.floor(player.z));var blockTarget=new Point(Math.floor(target.x),Math.floor(target.y),Math.floor(target.z));return blockPlayer.distanceSquared(blockTarget)<configuredRadius;
    }
}
