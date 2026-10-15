package com.avrgaming.civcraft.modern.town;

public record UpgradeDefinition(String id,String name,long cost,String action,String value,
                                String previous,String technology,String structure,String category) {
    public boolean supported() {
        return action.equals("set_town_level") || action.equals("set_bank_level") || action.equals("set_bank_interest") || action.equals("set_grocer_level") || action.equals("set_tradeship_upgrade_level") || action.equals("set_library_level") || action.equals("enable_library_enchantment") || action.equals("set_fish_hatchery_level") || action.equals("set_trommel_level") || action.equals("set_quarry_level");
    }
}
