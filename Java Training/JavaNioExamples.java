import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.IntBuffer;

public class JavaNioExamples {

    public static void main(String[] args) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(42);
        buffer.putInt(99);
        buffer.flip();

        System.out.println("first int: " + buffer.getInt());
        System.out.println("second int: " + buffer.getInt());

        IntBuffer ints = IntBuffer.allocate(3);
        ints.put(10).put(20).put(30);
        ints.flip();
        while (ints.hasRemaining()) {
            System.out.println("int buffer value: " + ints.get());
        }

        CharBuffer chars = CharBuffer.wrap("Java NIO");
        System.out.println("char buffer length: " + chars.length());
        chars.position(5);
        System.out.println("slice from position 5: " + chars.slice());

        ByteBuffer direct = ByteBuffer.allocateDirect(8);
        direct.putLong(123456789L);
        direct.flip();
        System.out.println("direct buffer long: " + direct.getLong());
    }
}
