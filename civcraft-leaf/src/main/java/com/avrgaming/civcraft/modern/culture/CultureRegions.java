package com.avrgaming.civcraft.modern.culture;
import java.util.*;

/** Breadth-first expansion and incumbent comparison from CultureProcessAsyncTask. */
public final class CultureRegions {
    private CultureRegions() {}
    public record Cell(String world,int x,int z) {
        public int distance(Cell other){return world.equals(other.world)?Math.abs(x-other.x)+Math.abs(z-other.z):Integer.MAX_VALUE;}
    }
    public record Town(long id,Cell origin,long culture,CultureLevels.Level level) {}
    public static Map<Cell,Long> calculate(List<Town> towns,Map<Cell,Long> plots,Map<Cell,Long> previous) {
        var byId=new HashMap<Long,Town>();towns.forEach(t->byId.put(t.id(),t));
        var ownership=new HashMap<Cell,Long>();previous.forEach((cell,id)->{if(byId.containsKey(id))ownership.put(cell,id);});
        int[][] offsets={{-1,0},{1,0},{0,-1},{0,1}};
        for(var town:towns) {
            var expanded=new HashSet<Cell>();var closed=new HashSet<Cell>();var queue=new ArrayDeque<Cell>();
            ownership.put(town.origin(),town.id());expanded.add(town.origin());queue.add(town.origin());
            while(!queue.isEmpty()) {
                var node=queue.removeFirst();if(!closed.add(node))continue;
                if(node.distance(town.origin())>town.level().radius())break;
                for(var offset:offsets) {
                    var next=new Cell(node.world(),node.x()+offset[0],node.z()+offset[1]);
                    Long plot=plots.get(next);if(plot!=null && plot!=town.id())continue;
                    Long incumbent=ownership.get(next);
                    if(incumbent==null) {
                        if(node.distance(town.origin())+1>=town.level().radius())continue;
                        ownership.put(next,town.id());expanded.add(next);
                    } else if(incumbent!=town.id()) {
                        var other=byId.get(incumbent);double currentPower=power(town,node,plots),otherPower=power(other,next,plots);
                        if(currentPower<otherPower || (currentPower==otherPower && town.culture()<=other.culture()))continue;
                        ownership.put(next,town.id());expanded.add(next);
                    } else if(next.distance(town.origin())<town.level().radius())expanded.add(next);
                    if(!closed.contains(next))queue.addLast(next);
                }
            }
            ownership.entrySet().removeIf(e->e.getValue()==town.id() && !expanded.contains(e.getKey()));
        }
        return Map.copyOf(ownership);
    }
    private static double power(Town town,Cell cell,Map<Cell,Long> plots) {
        int distance=Objects.equals(plots.get(cell),town.id())?0:cell.distance(town.origin());
        return distance==0?Double.MAX_VALUE:town.level().amount()/Math.pow(distance,2);
    }
}
