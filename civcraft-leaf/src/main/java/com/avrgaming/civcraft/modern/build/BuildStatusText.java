package com.avrgaming.civcraft.modern.build;

/** Player-facing labels; persisted state identifiers remain unchanged. */
public final class BuildStatusText {
    private BuildStatusText() {}
    public static String name(String status) {
        if(status==null)return "состояние неизвестно";
        return switch(status) {
            case "QUEUED" -> "в очереди";
            case "BUILDING" -> "строится";
            case "COMPLETE" -> "готово";
            case "ROLLBACK_PENDING" -> "восстанавливается местность";
            case "CANCELLED" -> "отменено";
            case "DEMOLISH_PENDING","DEMOLISHING" -> "разбирается";
            case "DEMOLISHED" -> "снесено";
            case "WAR_DESTROY_PENDING" -> "разрушается после боя";
            case "WAR_DESTROYED" -> "разрушено в бою";
            case "WAR_REPAIR_PENDING" -> "ремонтируется";
            case "CAMP_RESTORE_PENDING" -> "восстанавливается местность лагеря";
            default -> "неизвестное состояние ("+status+")";
        };
    }
}
