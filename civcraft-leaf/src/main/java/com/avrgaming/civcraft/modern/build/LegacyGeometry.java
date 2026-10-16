package com.avrgaming.civcraft.modern.build;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Legacy centers use integer halves of the template dimensions, including height. */
public final class LegacyGeometry {
    private LegacyGeometry() {}
    public record Offset(int x,int y,int z) {}
    private static final Map<String,Offset> centers=new ConcurrentHashMap<>();
    public static Offset center(String path)throws SQLException {
        if(path==null || path.isBlank())throw new SQLException("Missing structure template geometry");
        var cached=centers.get(path);if(cached!=null)return cached;
        if(!path.toLowerCase(java.util.Locale.ROOT).endsWith(".def")) {
            try{var template=WorldEditSchematicTemplate.load(Path.of(path));var result=new Offset(template.sizeX()/2,template.sizeY()/2,template.sizeZ()/2);centers.put(path,result);return result;}
            catch(java.io.IOException|RuntimeException|LinkageError failure){throw new SQLException("Unable to read schematic geometry: "+path,failure);}
        }
        try(var reader=Files.newBufferedReader(Path.of(path))){String line=reader.readLine();if(line==null)throw new IllegalArgumentException("Empty template");String[] shape=line.split(";",-1);if(shape.length!=3)throw new IllegalArgumentException("Unsupported legacy template header");int x=Integer.parseInt(shape[0].trim()),y=Integer.parseInt(shape[1].trim()),z=Integer.parseInt(shape[2].trim());if(x<1 || y<1 || z<1 || x>256 || y>256 || z>256)throw new IllegalArgumentException("Invalid template dimensions");var result=new Offset(x/2,y/2,z/2);centers.put(path,result);return result;}
        catch(java.io.IOException|RuntimeException failure){throw new SQLException("Unable to read structure geometry: "+path,failure);}
    }
}
