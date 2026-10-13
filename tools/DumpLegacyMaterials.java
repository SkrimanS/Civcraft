import org.bukkit.Material;
public class DumpLegacyMaterials {
    public static void main(String[] args) {
        for(Material material:Material.values())System.out.println(material.getId()+"="+material.name());
    }
}
