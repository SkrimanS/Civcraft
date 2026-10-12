package com.avrgaming.civcraft.modern.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LegacyDefTemplateLoader {
    public LegacyDefTemplate load(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IOException("Empty legacy .def template: " + path);
            }
            String[] dimensions = header.trim().split(";");
            if (dimensions.length != 3) {
                throw new IOException("Invalid .def header: " + header);
            }
            int sizeX = Integer.parseInt(dimensions[0]);
            int sizeY = Integer.parseInt(dimensions[1]);
            int sizeZ = Integer.parseInt(dimensions[2]);
            List<LegacyBlock> blocks = new ArrayList<>(sizeX * sizeY * sizeZ);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                blocks.add(parseBlock(line));
            }
            return new LegacyDefTemplate(sizeX, sizeY, sizeZ, blocks);
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
        List<String> signLines = List.of();
        if (coordAndBlock.length == 3) {
            signLines = Arrays.stream(coordAndBlock[2].split(",", -1)).limit(4).toList();
        }
        return new LegacyBlock(
                Integer.parseInt(coords[0]),
                Integer.parseInt(coords[1]),
                Integer.parseInt(coords[2]),
                Integer.parseInt(block[0]),
                (byte) Integer.parseInt(block[1]),
                signLines
        );
    }
}
