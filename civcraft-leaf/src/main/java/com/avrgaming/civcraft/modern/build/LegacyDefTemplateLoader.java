package com.avrgaming.civcraft.modern.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

public final class LegacyDefTemplateLoader {
    // Loading thousands of legacy files on the main thread or allocating a corrupt
    // dimension header can exhaust heap memory. Valid large Capitols are below this.
    private static final int MAX_AXIS = 256;
    private static final int MAX_BLOCKS = 2_000_000;
    public List<LegacyBlock> readChestMarkers(Path path)throws IOException {
        try(var reader=Files.newBufferedReader(path)) {
            String header=reader.readLine();if(header==null)throw new IOException("Empty template");
            String[] shape=header.split(";",-1);if(shape.length!=3)throw new IOException("Invalid template header");
            int x,y,z;try{x=Integer.parseInt(shape[0].trim());y=Integer.parseInt(shape[1].trim());z=Integer.parseInt(shape[2].trim());}catch(NumberFormatException invalid){throw new IOException("Invalid template size",invalid);}
            if(x<1 || y<1 || z<1 || x>MAX_AXIS || y>MAX_AXIS || z>MAX_AXIS || (long)x*y*z>MAX_BLOCKS)throw new IOException("Unsupported template size");
            var markers=new ArrayList<LegacyBlock>();String line;
            while((line=reader.readLine())!=null)if(line.toLowerCase(java.util.Locale.ROOT).contains("/chest")) {
                var block=parseBlock(line);if(block.x()<0 || block.x()>=x || block.y()<0 || block.y()>=y || block.z()<0 || block.z()>=z)throw new IOException("Chest marker outside template");
                if(ChestMarker.parse(block.signLines()).isPresent())markers.add(block);
            }
            return List.copyOf(markers);
        }
    }

    public LegacyDefTemplate load(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IOException("Empty legacy .def template: " + path);
            }
            String[] dimensions = header.trim().split(";", -1);
            if (dimensions.length != 3) {
                throw new IOException("Invalid .def header: " + header + " in " + path);
            }
            try {
                int sizeX = Integer.parseInt(dimensions[0].trim());
                int sizeY = Integer.parseInt(dimensions[1].trim());
                int sizeZ = Integer.parseInt(dimensions[2].trim());
                if (sizeX < 1 || sizeY < 1 || sizeZ < 1
                        || sizeX > MAX_AXIS || sizeY > MAX_AXIS || sizeZ > MAX_AXIS) {
                    throw new IOException("Invalid legacy .def dimensions " + header + " in " + path);
                }
                int volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
                if (volume > MAX_BLOCKS) {
                    throw new IOException("Legacy .def too large (" + volume + " blocks): " + path);
                }

                List<LegacyBlock> blocks = new ArrayList<>(volume);
                BitSet occupied = new BitSet(volume);
                String line;
                int lineNumber = 1;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) {
                        continue;
                    }
                    LegacyBlock block;
                    try {
                        block = parseBlock(line);
                    } catch (NumberFormatException exception) {
                        throw new IOException("Invalid number in " + path + " at line " + lineNumber, exception);
                    } catch (IOException exception) {
                        throw new IOException(path + ":" + lineNumber + ": " + exception.getMessage(), exception);
                    }
                    if (block.x() < 0 || block.x() >= sizeX
                            || block.y() < 0 || block.y() >= sizeY
                            || block.z() < 0 || block.z() >= sizeZ) {
                        throw new IOException("Block out of bounds at " + path + ":" + lineNumber);
                    }
                    if (block.legacyId() < 0 || block.legacyId() > 4095) {
                        throw new IOException("Invalid legacy block ID at " + path + ":" + lineNumber);
                    }
                    int index = (block.y() * sizeZ + block.z()) * sizeX + block.x();
                    if (occupied.get(index)) {
                        throw new IOException("Duplicate block coordinate in " + path + " at line " + lineNumber);
                    }
                    occupied.set(index);
                    blocks.add(block);
                }
                return new LegacyDefTemplate(sizeX, sizeY, sizeZ, blocks);
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new IOException("Malformed .def dimensions in " + path + ": " + header, exception);
            }
        }
    }

    private LegacyBlock parseBlock(String line) throws IOException {
        String[] coordAndBlock = line.split(",", 3);
        if (coordAndBlock.length < 2) {
            throw new IOException("Invalid block line: " + line);
        }
        String[] coords = coordAndBlock[0].split(":");
        String[] block = coordAndBlock[1].split(":");
        if (coords.length != 3 || block.length != 2) {
            throw new IOException("Invalid block line: " + line);
        }
        int data = Integer.parseInt(block[1]);
        if (data < 0 || data > 255) {
            throw new IOException("Legacy data byte out of range: " + data);
        }
        List<String> signLines = List.of();
        if (coordAndBlock.length == 3) {
            signLines = Arrays.stream(coordAndBlock[2].split(",", -1)).limit(4).toList();
        }
        return new LegacyBlock(
                Integer.parseInt(coords[0]),
                Integer.parseInt(coords[1]),
                Integer.parseInt(coords[2]),
                Integer.parseInt(block[0]),
                (byte) data,
                signLines
        );
    }
}
