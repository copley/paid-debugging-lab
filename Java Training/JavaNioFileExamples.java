import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public class JavaNioFileExamples {

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("java-nio-file-example");
        Path file = directory.resolve("notes.txt");

        Files.write(file, "line one\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
        Files.write(file, "line two\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        System.out.println("file path: " + file);
        System.out.println("file name: " + file.getFileName());
        System.out.println("parent: " + file.getParent());
        System.out.println("lines: " + lines);
        System.out.println("size: " + Files.size(file));
        System.out.println("absolute path example: " + Paths.get(".").toAbsolutePath().normalize());

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                System.out.println("visited file: " + path.getFileName() + ", bytes=" + attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });

        Files.deleteIfExists(file);
        Files.deleteIfExists(directory);
    }
}
