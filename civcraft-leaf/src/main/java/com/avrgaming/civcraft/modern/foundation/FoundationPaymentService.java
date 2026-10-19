package com.avrgaming.civcraft.modern.foundation;

import com.avrgaming.civcraft.modern.CivCraftModernPlugin;
import java.sql.SQLException;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** Never repeats an externally dispatched operation with an unknown result. */
public final class FoundationPaymentService {
    private FoundationPaymentService() {}
    public static void charge(CivCraftModernPlugin plugin,String id,Player player)throws SQLException {
        if(plugin.storage().query(db->FoundationPaymentStore.paid(db,id)))return;
        var receipt=plugin.storage().query(db->FoundationPaymentStore.find(db,id));
        if(receipt==null || !receipt.player().equals(player.getUniqueId()) || !receipt.mode().equals("VAULT") || !receipt.state().equals("WAITING"))
            throw new IllegalArgumentException("Оплата основания ещё не подтверждена. Требуется сверка платежа.");
        dispatch(plugin,receipt,player,false);
        if(!plugin.storage().query(db->FoundationPaymentStore.paid(db,id)))throw new IllegalArgumentException("Провайдер отклонил списание монет за основание города.");
    }
    public static void refund(CivCraftModernPlugin plugin,String id)throws SQLException {
        var receipt=plugin.storage().query(db->FoundationPaymentStore.find(db,id));
        if(receipt==null || !receipt.mode().equals("VAULT") || !receipt.state().equals("REFUND_PENDING"))return;
        if(!plugin.storage().query(db->FoundationCreationRollback.completed(db,id)))throw new SQLException("Foundation must be compensated before money is returned");
        dispatch(plugin,receipt,plugin.getServer().getOfflinePlayer(receipt.player()),true);
    }
    private static void dispatch(CivCraftModernPlugin plugin,FoundationPaymentStore.Receipt receipt,OfflinePlayer player,boolean refund)throws SQLException {
        plugin.economy().requireFoundationProvider(receipt.provider(),receipt.currency());
        plugin.storage().transaction(db->{FoundationPaymentStore.claim(db,receipt.id(),refund);return null;});
        try {
            var response=plugin.economy().foundationVaultPayment(player,receipt.amount(),receipt.provider(),receipt.currency(),refund);
            if(response==null)throw new SQLException("Vault returned no foundation payment result");
            plugin.storage().transaction(db->{FoundationPaymentStore.acknowledged(db,receipt.id(),refund,response.transactionSuccess(),response.errorMessage);return null;});
        }catch(SQLException|RuntimeException failure) {
            try{plugin.storage().transaction(db->{FoundationPaymentStore.uncertain(db,receipt.id(),refund,failure.getMessage());return null;});}
            catch(SQLException|RuntimeException logFailure){failure.addSuppressed(logFailure);}
            throw failure;
        }
    }
}
