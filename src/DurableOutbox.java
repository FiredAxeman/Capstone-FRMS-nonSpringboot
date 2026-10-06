import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Atomic local write journal. A command is removed only after the SQL transaction commits.
 */
public final class DurableOutbox {
    public record Entry(Path path, PendingWrite write) {
    }

    private final Path directory;
    private long sequence;

    public DurableOutbox(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        protect(this.directory, true);
        for (Entry entry : entries()) {
            sequence = Math.max(sequence, Long.parseLong(entry.path().getFileName().toString().substring(0, 19)));
        }
    }

    public Path getDirectory() {
        return directory;
    }

    public synchronized Entry enqueue(PendingWrite write) throws IOException {
        if (entries().size() >= 1000) {
            throw new IOException("The local queue is full. Synchronize or resolve pending changes.");
        }
        sequence = Math.max(System.currentTimeMillis(), sequence + 1);
        Path destination = directory.resolve(String.format(java.util.Locale.ROOT, "%019d-%s.xml", sequence, write.id()));
        Path temporary = Files.createTempFile(directory, ".write-", ".tmp");
        try {
            protect(temporary, false);
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                write.toProperties().storeToXML(output, "FRMS pending operational write", "UTF-8");
                output.getChannel().force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Entry(destination, write);
    }

    public synchronized List<Entry> entries() throws IOException {
        List<Entry> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files.filter(file -> file.getFileName().toString().matches("[0-9]{19}-[0-9a-f-]+\\.xml"))
                .sorted(Comparator.comparing(file -> file.getFileName().toString())).toList()) {
                if (Files.size(path) > 1_000_000) {
                    throw new IOException("Pending command is too large: " + path.getFileName());
                }
                Properties data = new Properties();
                try (InputStream input = Files.newInputStream(path)) {
                    data.loadFromXML(input);
                }
                try {
                    result.add(new Entry(path, PendingWrite.fromProperties(data)));
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid pending command: " + path.getFileName(), exception);
                }
            }
        }
        return List.copyOf(result);
    }

    public synchronized void remove(Entry entry) throws IOException {
        if (!entry.path().toAbsolutePath().normalize().getParent().equals(directory)) {
            throw new IOException("Invalid queue path.");
        }
        Files.deleteIfExists(entry.path());
    }

    private static void protect(Path path, boolean isDirectory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(isDirectory ? "rwx------" : "rw-------"));
        }
    }
}
