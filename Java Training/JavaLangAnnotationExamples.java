import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

public class JavaLangAnnotationExamples {

    public static void main(String[] args) throws Exception {
        Method method = Job.class.getDeclaredMethod("run");
        TrainingInfo info = method.getAnnotation(TrainingInfo.class);
        System.out.println("annotation topic: " + info.topic());
        System.out.println("annotation level: " + info.level());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface TrainingInfo {
        String topic();
        String level() default "intermediate";
    }

    public static final class Job {
        @TrainingInfo(topic = "annotations", level = "senior")
        public void run() {
            System.out.println("running");
        }
    }
}
