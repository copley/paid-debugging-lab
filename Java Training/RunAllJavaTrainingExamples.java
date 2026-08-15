import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class RunAllJavaTrainingExamples {

    public static void main(String[] args) throws Exception {
        List<String> examples = Arrays.asList(
                "JavaLangExamples",
                "JavaUtilCollectionsExamples",
                "JavaUtilConcurrentExamples",
                "JavaUtilConcurrentAtomicExamples",
                "JavaUtilConcurrentLocksExamples",
                "JavaIoExamples",
                "JavaNioExamples",
                "JavaNioFileExamples",
                "JavaNioCharsetExamples",
                "JavaTimeExamples",
                "JavaMathExamples",
                "JavaNetExamples",
                "JavaSqlExamples",
                "JavaUtilRegexExamples",
                "JavaUtilFunctionExamples",
                "JavaUtilStreamExamples",
                "JavaUtilLoggingExamples",
                "JavaTextExamples",
                "JavaSecurityExamples",
                "JavaxCryptoExamples",
                "JavaxNetSslExamples",
                "JavaXmlExamples",
                "JavaLangReflectExamples",
                "JavaLangAnnotationExamples",
                "JavaLangManagementExamples"
        );

        for (String className : examples) {
            System.out.println();
            System.out.println("=== " + className + " ===");
            runMain(className);
        }
    }

    private static void runMain(String className) throws Exception {
        Class<?> type = Class.forName(className);
        Method main = type.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw ex;
        }
    }
}
