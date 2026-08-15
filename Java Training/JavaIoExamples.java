import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;

public class JavaIoExamples {

    public static void main(String[] args) throws Exception {
        File file = File.createTempFile("java-io-example", ".txt");
        file.deleteOnExit();

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write("hello from java.io");
            writer.newLine();
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            System.out.println("file line: " + reader.readLine());
        }

        byte[] raw = "memory stream".getBytes(StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(raw), StandardCharsets.UTF_8))) {
            System.out.println("byte array stream: " + reader.readLine());
        }

        ByteArrayOutputStream objectBytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(objectBytes)) {
            out.writeObject(new Person("Max", 37));
        }

        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(objectBytes.toByteArray()))) {
            Person restored = (Person) in.readObject();
            System.out.println("serialized object: " + restored);
        }
    }

    private static final class Person implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final int age;

        private Person(String name, int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public String toString() {
            return name + " age=" + age;
        }
    }
}
