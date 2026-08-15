# Java Training

Runnable Java 8 examples for core JDK packages a senior Java engineer should know.

## How to use in Eclipse

1. Clone or download this repository.
2. In Eclipse, create or open a Java project.
3. Import the `Java Training` folder or copy the `.java` files into `src`.
4. Open any class and run it with **Run As → Java Application**.

All classes intentionally use the default package so they are easy to paste into Eclipse and run one at a time.

## Command-line check

From the repository root:

```bash
javac "Java Training"/*.java
java -cp "Java Training" RunAllJavaTrainingExamples
```

## Classes

| Class | Package area |
| --- | --- |
| `JavaLangExamples` | `java.lang` |
| `JavaUtilCollectionsExamples` | `java.util` collections and utilities |
| `JavaUtilConcurrentExamples` | `java.util.concurrent` |
| `JavaUtilConcurrentAtomicExamples` | `java.util.concurrent.atomic` |
| `JavaUtilConcurrentLocksExamples` | `java.util.concurrent.locks` |
| `JavaIoExamples` | `java.io` |
| `JavaNioExamples` | `java.nio` buffers |
| `JavaNioFileExamples` | `java.nio.file` |
| `JavaNioCharsetExamples` | `java.nio.charset` |
| `JavaTimeExamples` | `java.time` |
| `JavaMathExamples` | `java.math` |
| `JavaNetExamples` | `java.net` |
| `JavaSqlExamples` | `java.sql` |
| `JavaUtilRegexExamples` | `java.util.regex` |
| `JavaUtilFunctionExamples` | `java.util.function` |
| `JavaUtilStreamExamples` | `java.util.stream` |
| `JavaUtilLoggingExamples` | `java.util.logging` |
| `JavaTextExamples` | `java.text` |
| `JavaSecurityExamples` | `java.security` |
| `JavaxCryptoExamples` | `javax.crypto` |
| `JavaxNetSslExamples` | `javax.net.ssl` |
| `JavaXmlExamples` | `javax.xml`, `org.w3c.dom`, `org.xml.sax` |
| `JavaLangReflectExamples` | `java.lang.reflect` |
| `JavaLangAnnotationExamples` | `java.lang.annotation` |
| `JavaLangManagementExamples` | `java.lang.management` |
| `RunAllJavaTrainingExamples` | smoke-runs the full set |
