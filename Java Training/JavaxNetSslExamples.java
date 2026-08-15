import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

public class JavaxNetSslExamples {

    public static void main(String[] args) throws Exception {
        SSLContext context = SSLContext.getDefault();
        SSLSocketFactory factory = context.getSocketFactory();
        String[] cipherSuites = factory.getDefaultCipherSuites();
        System.out.println("default cipher suite count: " + cipherSuites.length);
        System.out.println("first few cipher suites: " + Arrays.toString(Arrays.copyOf(cipherSuites, Math.min(3, cipherSuites.length))));

        SSLParameters parameters = context.getDefaultSSLParameters();
        System.out.println("protocols: " + Arrays.toString(parameters.getProtocols()));
        System.out.println("endpoint identification algorithm before setting: " + parameters.getEndpointIdentificationAlgorithm());
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        System.out.println("endpoint identification algorithm after setting: " + parameters.getEndpointIdentificationAlgorithm());
    }
}
