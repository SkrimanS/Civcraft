package com.avrgaming.civcraft.modern.camp;

import com.avrgaming.civcraft.modern.foundation.FoundationCreationProtection;
import com.avrgaming.civcraft.modern.foundation.FoundationCreationStore;
import java.sql.*;
import java.util.Set;

/** NPCs/workstations require completed world placement, beyond logical commit. */
public final class CampReadinessStore {
    private CampReadinessStore() {}
    /** SQL fragment with one parameter: the configured camp structure id. */
    public static String ready(Connection db,String alias)throws SQLException {
        if(!Set.of("camps","c").contains(alias))throw new SQLException("Unknown camp readiness alias");
        String recorded=FoundationCreationStore.installed(db)
            ? " OR EXISTS(SELECT 1 FROM foundation_creation_plans ready_plan JOIN foundation_escrows ready_escrow ON ready_escrow.id=ready_plan.escrow_id WHERE ready_plan.created_camp="+alias+".id AND ready_plan.created_build=ready_camp.id AND ready_escrow.state='COMMITTED')" : "";
        return FoundationCreationProtection.visible(db,"camps",alias+".id")
            +" AND EXISTS(SELECT 1 FROM structure_builds ready_camp WHERE (ready_camp.structure_id=?"+recorded+") AND ready_camp.status='COMPLETE' AND ready_camp.world="+alias+".world AND ready_camp.x="+alias+".x AND ready_camp.y="+alias+".y AND ready_camp.z="+alias+".z)";
    }
}
