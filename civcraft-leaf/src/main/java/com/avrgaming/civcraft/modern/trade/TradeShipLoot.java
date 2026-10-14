package com.avrgaming.civcraft.modern.trade;

import java.util.*;
import java.util.random.RandomGenerator;

/** Return cargo from the original TradeLevelComponent.processItemsFromStack. */
public final class TradeShipLoot {
    private TradeShipLoot() {}
    public record Chances(double pack,double medium,double big,double huge) {
        public Chances {
            for(double value:new double[]{pack,medium,big,huge})
                if(!Double.isFinite(value)||value<0||value>1) throw new IllegalArgumentException("Invalid trade cargo chance");
        }
    }
    public record Reward(String item,int amount,boolean custom) {}
    public static List<Reward> roll(String item,int amount,Chances chances,RandomGenerator random) {
        if(item==null || amount<0) throw new IllegalArgumentException("Invalid trade cargo");
        List<Reward> result=new ArrayList<>();
        boolean egg=item.contains("_egg");
        int tier=item.contains("_egg_4")?4:item.contains("_egg_3")?3:item.contains("_egg_2")?2:1;
        for(int i=0;i<amount;i++) {
            int value=random.nextInt(10000);
            int pack=value<(int)(chances.huge()*10000)?4:
                    value<(int)(chances.big()*10000)?3:
                    value<(int)(chances.medium()*10000)?2:
                    value<(int)(chances.pack()*10000)?1:0;
            if(pack==0) continue;
            if(egg) {
                String suffix=pack==1?"_fragment_":"_";
                add(result,"mat_metallic_crystal"+suffix+tier,pack==4?2:1);
                add(result,"mat_ionic_crystal"+suffix+tier,pack==4?2:1);
                // Keep the original literal zombie check: it does not match zombie egg IDs.
                boolean hostile=item.contains("creeper")||item.contains("skeleton")||item.contains("spider")
                        ||item.equals("zombie")||item.contains("slime")||item.contains("enderman");
                boolean animal=item.contains("pig")||item.contains("cow")||item.contains("chicken")||item.contains("sheep");
                if(pack==4 && (hostile||animal)) {
                    String material=hostile?new String[]{"iron","steel","carbide_steel","tungsten"}[tier-1]:
                            new String[]{"leather","refined_leather","hardened_leather","composite_leather"}[tier-1];
                    for(String piece:hostile?List.of("chestplate","leggings","helmet","boots"):List.of("leggings","chestplate","helmet","boots"))
                        add(result,"mat_"+material+"_"+piece,1);
                } else if(pack==3 && (hostile||animal)) {
                    String equipment=hostile?new String[]{"iron_sword","steel_sword","carbide_steel_sword","tungsten_sword"}[tier-1]:
                            new String[]{"hunting_bow","recurve_bow","longbow","marksmen_bow"}[tier-1];
                    add(result,"mat_"+equipment,1);
                }
                // Egg cargo stops rolling this stack after its first successful reward.
                break;
            }
            boolean powder=random.nextInt(4)+1>=3;
            String material=powder?"GUNPOWDER":new String[]{"IRON_INGOT","GOLD_INGOT","DIAMOND","EMERALD"}[pack-1];
            result.add(new Reward(material,powder?new int[]{1,2,2,3}[pack-1]:1,false));
            if(pack==2 || pack==3) {
                add(result,"mat_metallic_crystal_fragment_"+pack,random.nextInt(3)+1);
                add(result,"mat_ionic_crystal_fragment_"+pack,random.nextInt(3)+1);
            }
        }
        return List.copyOf(result);
    }
    private static void add(List<Reward> target,String item,int amount) {
        // Original reward code used lower case, but materials.yml uses this exact ID.
        if(item.equals("mat_leather_boots")) item="mat_leather_Boots";
        target.add(new Reward(item,amount,true));
    }
}
