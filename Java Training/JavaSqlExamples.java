import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.Enumeration;

public class JavaSqlExamples {

    public static void main(String[] args) {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        int count = 0;
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            System.out.println("loaded JDBC driver: " + driver.getClass().getName());
            count++;
        }
        System.out.println("driver count: " + count);

        try {
            DriverManager.getConnection("jdbc:invalid:demo");
        } catch (SQLException ex) {
            System.out.println("expected JDBC failure: " + ex.getClass().getSimpleName());
            System.out.println("SQLState: " + ex.getSQLState());
        }

        System.out.println("common JDBC types: VARCHAR=" + Types.VARCHAR + ", INTEGER=" + Types.INTEGER);
        System.out.println("key interfaces: Connection, PreparedStatement, ResultSet");
        System.out.println("always close JDBC resources with try-with-resources");
        System.out.println("example shape: try (Connection c = dataSource.getConnection()) { ... }");
    }

    @SuppressWarnings("unused")
    private static void typicalQueryShape(Connection connection, String name) throws SQLException {
        String sql = "select id, name from users where name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    System.out.println(rows.getLong("id") + ": " + rows.getString("name"));
                }
            }
        } catch (SQLTimeoutException timeout) {
            System.out.println("query timed out: " + timeout.getMessage());
        }
    }
}
