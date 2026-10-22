package me.juancarloscp52.entropy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConfigFilesTest {
    @TempDir Path directory;

    @Test void createsDirectoriesAndReplacesUtf8Contents() throws Exception {
        Path file = directory.resolve("config/entropy.json");
        ConfigFiles.write(file, "{\"label\":\"votação\"}");
        assertEquals("{\"label\":\"votação\"}", Files.readString(file));
        ConfigFiles.write(file, "{}");
        assertEquals("{}", Files.readString(file));
        try (var entries = Files.list(file.getParent())) { assertEquals(1, entries.count()); }
    }

    @Test void failedReplaceLeavesDestinationIntactAndCleansTemporaryFile() throws Exception {
        Path destination = directory.resolve("existing-directory");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("keep.txt"), "keep");
        assertThrows(java.io.IOException.class, () -> ConfigFiles.write(destination, "{}"));
        assertEquals("keep", Files.readString(destination.resolve("keep.txt")));
        try (var entries = Files.list(directory)) { assertEquals(1, entries.count()); }
    }
}
