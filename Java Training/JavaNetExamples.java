import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;

public class JavaNetExamples {

    public static void main(String[] args) throws Exception {
        URI uri = new URI("https://example.com/search?q=java%20networking");
        System.out.println("scheme: " + uri.getScheme());
        System.out.println("host: " + uri.getHost());
        System.out.println("path: " + uri.getPath());
        System.out.println("query: " + uri.getQuery());

        URL url = uri.toURL();
        System.out.println("URL: " + url);

        String encoded = URLEncoder.encode("Java URL encoding", "UTF-8");
        String decoded = URLDecoder.decode(encoded, "UTF-8");
        System.out.println("encoded: " + encoded);
        System.out.println("decoded: " + decoded);

        InetAddress loopback = InetAddress.getLoopbackAddress();
        System.out.println("loopback host address: " + loopback.getHostAddress());

        InetSocketAddress address = new InetSocketAddress("localhost", 8080);
        System.out.println("socket address: " + address);
    }
}
