package com.avrgaming.civcraft.modern.trade;

import java.util.*;

/** Original TradeLevelComponent arithmetic, independent of chest mutations. */
public final class TradeShipProgress {
    private TradeShipProgress() {}
    public record Level(int level, int threshold, int stacks, double culture) {
        public Level {
            if (level < 1 || threshold < 1 || stacks < 1 || !Double.isFinite(culture) || culture < 0)
                throw new IllegalArgumentException("Invalid trade ship level");
        }
    }
    public record State(int level, int count) {
        public State {
            if (level < 1 || count < 0) throw new IllegalArgumentException("Invalid trade ship progress");
        }
    }
    public record Cargo(int amount, boolean tradeable, double value) {
        public Cargo {
            if (amount < 1 || !Double.isFinite(value) || value < 0)
                throw new IllegalArgumentException("Invalid trade ship cargo");
        }
    }
    public enum Result { STAGNATE, GROW, LEVELUP, MAXED }
    public record Outcome(State state, Result result, List<Integer> consumedSlots,
                          int consumed, double money, long culture) {}

    public static Outcome consume(Map<Integer,Level> levels, State state, int upgrade, List<Cargo> slots) {
        if (upgrade < 1 || upgrade > 6) throw new IllegalArgumentException("Invalid trade ship upgrade");
        Level current = Objects.requireNonNull(levels.get(state.level()),"Missing trade ship level");
        int remaining = Math.addExact(current.stacks(), (upgrade-1)*2);
        int consumed = 0;
        double money = 0;
        List<Integer> used = new ArrayList<>();
        for (int i=0; i<slots.size() && remaining>0; i++) {
            Cargo cargo = slots.get(i);
            if (cargo == null || !cargo.tradeable()) continue;
            used.add(i);
            consumed = Math.addExact(consumed,cargo.amount());
            money += cargo.amount()*cargo.value();
            remaining--;
        }
        if (!Double.isFinite(money)) throw new IllegalArgumentException("Invalid cargo monetary value");
        State next = state;
        Result result = Result.STAGNATE;
        if (consumed > 0) {
            int total = Math.addExact(state.count(),consumed);
            if (total >= current.threshold()) {
                if (levels.containsKey(state.level()+1)) {
                    next = new State(state.level()+1,total-current.threshold());
                    result = Result.LEVELUP;
                } else {
                    // Legacy MAXED leaves the previous counter intact, but still trades cargo.
                    result = Result.MAXED;
                }
            } else {
                next = new State(state.level(),total);
                result = Result.GROW;
            }
        }
        // Legacy applies the culture rate of the new level on the level-up tick.
        double rate = Objects.requireNonNull(levels.get(next.level())).culture()+(upgrade-1)/5.0;
        return new Outcome(next,result,List.copyOf(used),consumed,money,Math.round(rate*consumed));
    }
    public static boolean inputChest(int markerId,int upgrade) {return markerId>=0 && upgrade>=markerId+1;}
    public static boolean outputChest(int markerId,int level) {return markerId>=0 && level>=markerId*2L+1;}
}
