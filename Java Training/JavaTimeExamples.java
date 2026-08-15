import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public class JavaTimeExamples {

    public static void main(String[] args) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        LocalDateTime localDateTime = LocalDateTime.now();
        ZonedDateTime wellington = ZonedDateTime.now(ZoneId.of("Pacific/Auckland"));

        System.out.println("instant: " + now);
        System.out.println("today: " + today);
        System.out.println("local date-time: " + localDateTime);
        System.out.println("Wellington time: " + wellington);

        Duration twoHours = Duration.ofHours(2);
        Period oneMonth = Period.ofMonths(1);
        System.out.println("plus duration: " + now.plus(twoHours));
        System.out.println("plus period: " + today.plus(oneMonth));
        System.out.println("days until next week: " + ChronoUnit.DAYS.between(today, today.plusWeeks(1)));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
        System.out.println("formatted: " + wellington.format(formatter));

        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneId.of("UTC"));
        System.out.println("testable fixed date: " + LocalDate.now(fixedClock));
    }
}
