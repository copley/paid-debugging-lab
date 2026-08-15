import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

public class JavaLangReflectExamples {

    public static void main(String[] args) throws Exception {
        Class<Person> type = Person.class;
        Constructor<Person> constructor = type.getDeclaredConstructor(String.class, int.class);
        Person person = constructor.newInstance("Max", 37);

        Method greeting = type.getDeclaredMethod("greeting");
        System.out.println("method result: " + greeting.invoke(person));

        Field age = type.getDeclaredField("age");
        age.setAccessible(true);
        System.out.println("private field age: " + age.get(person));

        System.out.println("declared methods: " + Arrays.toString(type.getDeclaredMethods()));

        GreetingService proxy = (GreetingService) Proxy.newProxyInstance(
                JavaLangReflectExamples.class.getClassLoader(),
                new Class<?>[] { GreetingService.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return "dynamic proxy handled " + method.getName() + " for " + args[0];
                    }
                });
        System.out.println(proxy.sayHello("Java"));
    }

    public interface GreetingService {
        String sayHello(String name);
    }

    public static final class Person {
        private final String name;
        private final int age;

        public Person(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String greeting() {
            return "Hello " + name;
        }
    }
}
