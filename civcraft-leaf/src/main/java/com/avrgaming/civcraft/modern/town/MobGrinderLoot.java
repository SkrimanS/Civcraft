package com.avrgaming.civcraft.modern.town;

import java.io.File;
import java.util.*;
import java.util.random.RandomGenerator;
import org.bukkit.configuration.file.YamlConfiguration;

/** Original ordered MobGrinderAsyncTask rewards, including its equality checks and tier-specific packs. */
public final class MobGrinderLoot {
    public record Reward(String item,int amount) {}
    private enum Crystal {T1,T2,T3,T4,PACK,BIGPACK,HUGEPACK}
    private final Map<Crystal,Double> chances;
    private final double tribalism,penalty;
    public MobGrinderLoot(File file){
        var config=YamlConfiguration.loadConfiguration(file);var rates=new EnumMap<Crystal,Double>(Crystal.class);
        for(var crystal:Crystal.values()){double rate=config.getDouble("mobGrinder."+crystal.name().toLowerCase(Locale.ROOT)+"_chance");if(!Double.isFinite(rate) || rate<0 || rate>1)throw new IllegalArgumentException("Неверная вероятность дробилки");rates.put(crystal,rate);}
        chances=Map.copyOf(rates);tribalism=config.getDouble("mobGrinder.tribalism_rate");penalty=config.getDouble("mobGrinder.penalty_rate");
        if(!Double.isFinite(tribalism) || tribalism<0 || !Double.isFinite(penalty) || penalty<0)throw new IllegalArgumentException("Неверный модификатор правительства для дробилки");
    }
    private double chance(Crystal crystal,double extraction,String government){double value=chances.get(crystal);value+=value*extraction;return value*("gov_tribalism".equals(government)?tribalism:penalty);}
    public static boolean egg(String item){return item!=null && item.contains("_egg");}
    public Set<String> items(){return Set.of("mat_carbide_steel_boots","mat_carbide_steel_chestplate","mat_carbide_steel_helmet","mat_carbide_steel_leggings","mat_carbide_steel_sword","mat_chicken_egg_2","mat_chicken_egg_3","mat_chicken_egg_4","mat_composite_leather_boots","mat_composite_leather_chestplate","mat_composite_leather_helmet","mat_composite_leather_leggings","mat_cow_egg_2","mat_cow_egg_3","mat_cow_egg_4","mat_creeper_egg_2","mat_creeper_egg_3","mat_creeper_egg_4","mat_enderman_egg_2","mat_enderman_egg_3","mat_enderman_egg_4","mat_hardened_leather_boots","mat_hardened_leather_chestplate","mat_hardened_leather_helmet","mat_hardened_leather_leggings","mat_ionic_crystal_1","mat_ionic_crystal_2","mat_ionic_crystal_3","mat_ionic_crystal_4","mat_ionic_crystal_fragment_1","mat_ionic_crystal_fragment_2","mat_ionic_crystal_fragment_3","mat_ionic_crystal_fragment_4","mat_iron_boots","mat_iron_chestplate","mat_iron_helmet","mat_iron_leggings","mat_iron_sword","mat_leather_Boots","mat_leather_chestplate","mat_leather_helmet","mat_leather_leggings","mat_metallic_crystal_1","mat_metallic_crystal_2","mat_metallic_crystal_3","mat_metallic_crystal_4","mat_metallic_crystal_fragment_1","mat_metallic_crystal_fragment_2","mat_metallic_crystal_fragment_3","mat_metallic_crystal_fragment_4","mat_pig_egg_2","mat_pig_egg_3","mat_pig_egg_4","mat_refined_leather_boots","mat_refined_leather_chestplate","mat_refined_leather_helmet","mat_refined_leather_leggings","mat_sheep_egg_2","mat_sheep_egg_3","mat_sheep_egg_4","mat_skeleton_egg_2","mat_skeleton_egg_3","mat_skeleton_egg_4","mat_slime_egg_2","mat_slime_egg_3","mat_slime_egg_4","mat_spider_egg_2","mat_spider_egg_3","mat_spider_egg_4","mat_steel_boots","mat_steel_chestplate","mat_steel_helmet","mat_steel_leggings","mat_steel_sword","mat_tungsten_boots","mat_tungsten_chestplate","mat_tungsten_helmet","mat_tungsten_leggings","mat_tungsten_sword","mat_zombie_egg_2","mat_zombie_egg_3","mat_zombie_egg_4");}
    public List<Reward> roll(String itemID,double extraction,String government,RandomGenerator random){return select(itemID,extraction,government,random.nextInt(10000));}
    public List<Reward> select(String itemID,double extraction,String government,int rand1){
        if(itemID==null || !Double.isFinite(extraction) || extraction<0 || extraction>1000 || rand1<0 || rand1>=10000)throw new IllegalArgumentException("Неверный результат случайного выбора дробилки");
        var newItems=new ArrayList<Reward>();
        if (itemID.contains("_egg_4")) {
            if (rand1 < ((int)((chance(Crystal.HUGEPACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_1",2));
                newItems.add(new Reward("mat_ionic_crystal_1",2));
                newItems.add(new Reward("mat_metallic_crystal_2",2));
                newItems.add(new Reward("mat_ionic_crystal_2",2));
                newItems.add(new Reward("mat_metallic_crystal_3",2));
                newItems.add(new Reward("mat_ionic_crystal_3",2));
                newItems.add(new Reward("mat_metallic_crystal_4",2));
                newItems.add(new Reward("mat_ionic_crystal_4",2));
            } else if (rand1 < ((int)((chance(Crystal.BIGPACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_4",1));
                newItems.add(new Reward("mat_ionic_crystal_4",1));
                if (itemID.contains("creeper")) {
                    newItems.add(new Reward("mat_tungsten_chestplate",1));
                } else if (itemID.contains("skeleton")) {
                    newItems.add(new Reward("mat_tungsten_leggings",1));
                } else if (itemID.contains("spider")) {
                    newItems.add(new Reward("mat_tungsten_helmet",1));
                } else if (itemID.equals("zombie")) {
                    newItems.add(new Reward("mat_tungsten_boots",1));
                } else if (itemID.contains("slime")) {
                    newItems.add(new Reward("mat_tungsten_sword",1));
                } else if (itemID.contains("enderman")) {
                    newItems.add(new Reward("mat_tungsten_sword",1));
                } else if (itemID.contains("pig")) {
                    newItems.add(new Reward("mat_composite_leather_leggings",1));
                } else if (itemID.contains("cow")) {
                    newItems.add(new Reward("mat_composite_leather_chestplate",1));
                } else if (itemID.contains("chicken")) {
                    newItems.add(new Reward("mat_composite_leather_helmet",1));
                } else if (itemID.contains("sheep")) {
                    newItems.add(new Reward("mat_composite_leather_boots",1));
                }
            } else if (rand1 < ((int)((chance(Crystal.PACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_4",3));
                newItems.add(new Reward("mat_ionic_crystal_4",3));
            } else if (rand1 < ((int)((chance(Crystal.T4,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_4",2));
                newItems.add(new Reward("mat_ionic_crystal_4",2));
            } else if (rand1 < ((int)((chance(Crystal.T3,extraction,government))*10000))) {
                newItems.add(new Reward("mat_ionic_crystal_4",2));
            } else if (rand1 < ((int)((chance(Crystal.T2,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_fragment_4",5));
                newItems.add(new Reward("mat_ionic_crystal_fragment_4",5));
            } else if (rand1 < ((int)((chance(Crystal.T1,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_fragment_4",3));
                newItems.add(new Reward("mat_ionic_crystal_fragment_4",3));
            }  else {
                newItems.add(new Reward("mat_metallic_crystal_fragment_4",2));
                newItems.add(new Reward("mat_ionic_crystal_fragment_4",2));
            }
        } else if (itemID.contains("_egg_3")) {
            if (rand1 < ((int)((chance(Crystal.HUGEPACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_1",1));
                newItems.add(new Reward("mat_ionic_crystal_1",1));
                newItems.add(new Reward("mat_metallic_crystal_2",1));
                newItems.add(new Reward("mat_ionic_crystal_2",1));
                newItems.add(new Reward("mat_metallic_crystal_3",2));
                newItems.add(new Reward("mat_ionic_crystal_3",2));
                if (itemID.contains("creeper")) {
                    newItems.add(new Reward("mat_creeper_egg_4",2));
                } else if (itemID.contains("skeleton")) {
                    newItems.add(new Reward("mat_skeleton_egg_4",2));
                } else if (itemID.contains("spider")) {
                    newItems.add(new Reward("mat_spider_egg_4",2));
                } else if (itemID.equals("zombie")) {
                    newItems.add(new Reward("mat_zombie_egg_4",2));
                } else if (itemID.contains("slime")) {
                    newItems.add(new Reward("mat_slime_egg_4",2));
                } else if (itemID.contains("enderman")) {
                    newItems.add(new Reward("mat_enderman_egg_4",2));
                } else if (itemID.contains("pig")) {
                    newItems.add(new Reward("mat_pig_egg_4",2));
                } else if (itemID.contains("cow")) {
                    newItems.add(new Reward("mat_cow_egg_4",2));
                } else if (itemID.contains("chicken")) {
                    newItems.add(new Reward("mat_chicken_egg_4",2));
                } else if (itemID.contains("sheep")) {
                    newItems.add(new Reward("mat_sheep_egg_4",2));
                }
            } else if (rand1 < ((int)((chance(Crystal.BIGPACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_3",1));
                newItems.add(new Reward("mat_ionic_crystal_3",1));
                if (itemID.contains("creeper")) {
                    newItems.add(new Reward("mat_creeper_egg_4",1));
                    newItems.add(new Reward("mat_carbide_steel_chestplate",1));
                } else if (itemID.contains("skeleton")) {
                    newItems.add(new Reward("mat_skeleton_egg_4",1));
                    newItems.add(new Reward("mat_carbide_steel_leggings",1));
                } else if (itemID.contains("spider")) {
                    newItems.add(new Reward("mat_spider_egg_4",1));
                    newItems.add(new Reward("mat_carbide_steel_helmet",1));
                } else if (itemID.equals("zombie")) {
                    newItems.add(new Reward("mat_zombie_egg_4",1));
                    newItems.add(new Reward("mat_carbide_steel_boots",1));
                } else if (itemID.contains("slime")) {
                    newItems.add(new Reward("mat_slime_egg_4",1));
                    newItems.add(new Reward("mat_carbide_steel_sword",1));
                } else if (itemID.contains("enderman")) {
                    newItems.add(new Reward("mat_enderman_egg_4",1));
                    newItems.add(new Reward("mat_carbide_steel_sword",1));
                } else if (itemID.contains("pig")) {
                    newItems.add(new Reward("mat_pig_egg_4",1));
                    newItems.add(new Reward("mat_hardened_leather_leggings",1));
                } else if (itemID.contains("cow")) {
                    newItems.add(new Reward("mat_cow_egg_4",1));
                    newItems.add(new Reward("mat_hardened_leather_chestplate",1));
                } else if (itemID.contains("chicken")) {
                    newItems.add(new Reward("mat_chicken_egg_4",1));
                    newItems.add(new Reward("mat_hardened_leather_helmet",1));
                } else if (itemID.contains("sheep")) {
                    newItems.add(new Reward("mat_sheep_egg_4",1));
                    newItems.add(new Reward("mat_hardened_leather_boots",1));
                }
            } else if (rand1 < ((int)((chance(Crystal.PACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_3",1));
                newItems.add(new Reward("mat_ionic_crystal_3",1));
                newItems.add(new Reward("mat_metallic_crystal_4",1));
                newItems.add(new Reward("mat_ionic_crystal_4",1));
            } else if (rand1 < ((int)((chance(Crystal.T4,extraction,government))*10000))) {
                newItems.add(new Reward("mat_ionic_crystal_3",1));
                newItems.add(new Reward("mat_metallic_crystal_3",1));
            } else if (rand1 < ((int)((chance(Crystal.T3,extraction,government))*10000))) {
                newItems.add(new Reward("mat_ionic_crystal_3",1));
            } else if (rand1 < ((int)((chance(Crystal.T2,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_fragment_4",1));
                newItems.add(new Reward("mat_ionic_crystal_fragment_4",1));
                newItems.add(new Reward("mat_metallic_crystal_fragment_3",2));
                newItems.add(new Reward("mat_ionic_crystal_fragment_3",2));
            } else if (rand1 < ((int)((chance(Crystal.T1,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_fragment_3",3));
                newItems.add(new Reward("mat_ionic_crystal_fragment_3",3));
            }  else {
                newItems.add(new Reward("mat_metallic_crystal_fragment_3",1));
                newItems.add(new Reward("mat_ionic_crystal_fragment_3",1));
            }
        } else if (itemID.contains("_egg_2")) {
            if (rand1 < ((int)((chance(Crystal.HUGEPACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_2",2));
                newItems.add(new Reward("mat_ionic_crystal_2",2));
                if (itemID.equals("creeper")) {
                    newItems.add(new Reward("mat_creeper_egg_3",2));
                } else if (itemID.equals("skeleton")) {
                    newItems.add(new Reward("mat_skeleton_egg_3",2));
                } else if (itemID.equals("spider")) {
                    newItems.add(new Reward("mat_spider_egg_3",2));
                } else if (itemID.equals("zombie")) {
                    newItems.add(new Reward("mat_zombie_egg_3",2));
                } else if (itemID.contains("slime")) {
                    newItems.add(new Reward("mat_slime_egg_3",2));
                } else if (itemID.contains("enderman")) {
                    newItems.add(new Reward("mat_enderman_egg_3",2));
                } else if (itemID.contains("pig")) {
                    newItems.add(new Reward("mat_pig_egg_3",2));
                } else if (itemID.contains("cow")) {
                    newItems.add(new Reward("mat_cow_egg_3",2));
                } else if (itemID.contains("chicken")) {
                    newItems.add(new Reward("mat_chicken_egg_3",2));
                } else if (itemID.contains("sheep")) {
                    newItems.add(new Reward("mat_sheep_egg_3",2));
                }
            } else if (rand1 < ((int)((chance(Crystal.BIGPACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_2",1));
                newItems.add(new Reward("mat_ionic_crystal_2",1));
                if (itemID.contains("creeper")) {
                    newItems.add(new Reward("mat_creeper_egg_3",1));
                    newItems.add(new Reward("mat_steel_chestplate",1));
                } else if (itemID.contains("skeleton")) {
                    newItems.add(new Reward("mat_skeleton_egg_3",1));
                    newItems.add(new Reward("mat_steel_leggings",1));
                } else if (itemID.contains("spider")) {
                    newItems.add(new Reward("mat_spider_egg_3",1));
                    newItems.add(new Reward("mat_steel_helmet",1));
                } else if (itemID.contains("zombie")) {
                    newItems.add(new Reward("mat_zombie_egg_3",1));
                    newItems.add(new Reward("mat_steel_boots",1));
                } else if (itemID.contains("slime")) {
                    newItems.add(new Reward("mat_slime_egg_3",1));
                    newItems.add(new Reward("mat_steel_sword",1));
                } else if (itemID.contains("enderman")) {
                    newItems.add(new Reward("mat_enderman_egg_3",1));
                    newItems.add(new Reward("mat_steel_sword",1));
                } else if (itemID.contains("pig")) {
                    newItems.add(new Reward("mat_pig_egg_3",1));
                    newItems.add(new Reward("mat_refined_leather_leggings",1));
                } else if (itemID.contains("cow")) {
                    newItems.add(new Reward("mat_cow_egg_3",1));
                    newItems.add(new Reward("mat_refined_leather_chestplate",1));
                } else if (itemID.contains("chicken")) {
                    newItems.add(new Reward("mat_chicken_egg_3",1));
                    newItems.add(new Reward("mat_refined_leather_helmet",1));
                } else if (itemID.contains("sheep")) {
                    newItems.add(new Reward("mat_sheep_egg_3",1));
                    newItems.add(new Reward("mat_refined_leather_boots",1));
                }
            } else if (rand1 < ((int)((chance(Crystal.PACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_3",1));
                newItems.add(new Reward("mat_ionic_crystal_3",1));
                newItems.add(new Reward("mat_metallic_crystal_2",1));
                newItems.add(new Reward("mat_ionic_crystal_2",1));
            } else if (rand1 < ((int)((chance(Crystal.T4,extraction,government))*10000))) {
                newItems.add(new Reward("mat_ionic_crystal_2",1));
                newItems.add(new Reward("mat_metallic_crystal_2",1));
            } else if (rand1 < ((int)((chance(Crystal.T3,extraction,government))*10000))) {
                newItems.add(new Reward("mat_ionic_crystal_2",1));
            } else if (rand1 < ((int)((chance(Crystal.T2,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_fragment_3",1));
                newItems.add(new Reward("mat_ionic_crystal_fragment_3",1));
                newItems.add(new Reward("mat_metallic_crystal_fragment_2",2));
                newItems.add(new Reward("mat_ionic_crystal_fragment_2",2));
            } else if (rand1 < ((int)((chance(Crystal.T1,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_fragment_2",3));
                newItems.add(new Reward("mat_ionic_crystal_fragment_2",3));
            }  else {
                newItems.add(new Reward("mat_metallic_crystal_fragment_2",1));
                newItems.add(new Reward("mat_ionic_crystal_fragment_2",1));
            }
        } else if (itemID.contains("_egg")) {
            if (rand1 < ((int)((chance(Crystal.HUGEPACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_1",2));
                newItems.add(new Reward("mat_ionic_crystal_1",2));
                if (itemID.contains("creeper")) {
                    newItems.add(new Reward("mat_creeper_egg_2",2));
                } else if (itemID.contains("skeleton")) {
                    newItems.add(new Reward("mat_skeleton_egg_2",2));
                } else if (itemID.contains("spider")) {
                    newItems.add(new Reward("mat_spider_egg_2",2));
                } else if (itemID.contains("zombie")) {
                    newItems.add(new Reward("mat_zombie_egg_2",2));
                } else if (itemID.contains("slime")) {
                    newItems.add(new Reward("mat_slime_egg_2",2));
                } else if (itemID.contains("enderman")) {
                    newItems.add(new Reward("mat_enderman_egg_2",2));
                } else if (itemID.contains("pig")) {
                    newItems.add(new Reward("mat_pig_egg_2",2));
                } else if (itemID.contains("cow")) {
                    newItems.add(new Reward("mat_cow_egg_2",2));
                } else if (itemID.contains("chicken")) {
                    newItems.add(new Reward("mat_chicken_egg_2",2));
                } else if (itemID.contains("sheep")) {
                    newItems.add(new Reward("mat_sheep_egg_2",2));
                }
            }
            else if (rand1 < ((int)((chance(Crystal.BIGPACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_1",1));
                newItems.add(new Reward("mat_ionic_crystal_1",1));
                if (itemID.contains("creeper")) {
                    newItems.add(new Reward("mat_creeper_egg_2",1));
                    newItems.add(new Reward("mat_iron_chestplate",1));
                } else if (itemID.contains("skeleton")) {
                    newItems.add(new Reward("mat_skeleton_egg_2",1));
                    newItems.add(new Reward("mat_iron_leggings",1));
                } else if (itemID.contains("spider")) {
                    newItems.add(new Reward("mat_spider_egg_2",1));
                    newItems.add(new Reward("mat_iron_helmet",1));
                } else if (itemID.contains("zombie")) {
                    newItems.add(new Reward("mat_zombie_egg_2",1));
                    newItems.add(new Reward("mat_iron_boots",1));
                } else if (itemID.contains("slime")) {
                    newItems.add(new Reward("mat_slime_egg_2",1));
                    newItems.add(new Reward("mat_iron_sword",1));
                } else if (itemID.contains("enderman")) {
                    newItems.add(new Reward("mat_enderman_egg_2",1));
                    newItems.add(new Reward("mat_iron_sword",1));
                } else if (itemID.contains("pig")) {
                    newItems.add(new Reward("mat_pig_egg_2",1));
                    newItems.add(new Reward("mat_leather_leggings",1));
                } else if (itemID.contains("cow")) {
                    newItems.add(new Reward("mat_cow_egg_2",1));
                    newItems.add(new Reward("mat_leather_chestplate",1));
                } else if (itemID.contains("chicken")) {
                    newItems.add(new Reward("mat_chicken_egg_2",1));
                    newItems.add(new Reward("mat_leather_helmet",1));
                } else if (itemID.contains("sheep")) {
                    newItems.add(new Reward("mat_sheep_egg_2",1));
                    newItems.add(new Reward("mat_leather_Boots",1));
                }
            }
            else if (rand1 < ((int)((chance(Crystal.PACK,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_1",1));
                newItems.add(new Reward("mat_ionic_crystal_1",1));
                newItems.add(new Reward("mat_metallic_crystal_2",1));
                newItems.add(new Reward("mat_ionic_crystal_2",1));
            }
            else if (rand1 < ((int)((chance(Crystal.T4,extraction,government))*10000))) {
                newItems.add(new Reward("mat_ionic_crystal_1",1));
                newItems.add(new Reward("mat_metallic_crystal_1",1));
            }
            else if (rand1 < ((int)((chance(Crystal.T3,extraction,government))*10000))) {
                newItems.add(new Reward("mat_ionic_crystal_1",1));
            }
            else if (rand1 < ((int)((chance(Crystal.T2,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_fragment_2",1));
                newItems.add(new Reward("mat_ionic_crystal_fragment_2",1));
                newItems.add(new Reward("mat_metallic_crystal_fragment_1",2));
                newItems.add(new Reward("mat_ionic_crystal_fragment_1",2));
            }
            else if (rand1 < ((int)((chance(Crystal.T1,extraction,government))*10000))) {
                newItems.add(new Reward("mat_metallic_crystal_fragment_1",3));
                newItems.add(new Reward("mat_ionic_crystal_fragment_1",3));
            }  else {
                newItems.add(new Reward("mat_metallic_crystal_fragment_1",1));
                newItems.add(new Reward("mat_ionic_crystal_fragment_1",1));
            }
        } else {
            return List.of();
        }
        return List.copyOf(newItems);
    }
}
