package com.avrgaming.civcraft.object;

/**
 * Compatibility bridge for the legacy Eclipse-compiled classes stored in civcraft/bin.
 *
 * The current source version of TradeGood extends SQLObject directly, but the imported
 * legacy TradeGood.class can still reference this old intermediate superclass. Keeping
 * this thin abstract bridge lets the legacy package boot far enough to expose the next
 * real runtime issue while the full source-compile port is being repaired.
 */
public abstract class VaultEconObject extends SQLObject {
}
