package bpc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DB {
    private static final String URL = "jdbc:mysql://localhost:3306/bpc";
    private static final String USER = "bpc";
    private static final String PASSWORD = "1234";
    private static Connection conn;
    public static Connection get() throws SQLException {

        if (conn == null || conn.isClosed()) {
            try {
                conn = DriverManager.getConnection(URL, USER, PASSWORD);
            } catch (SQLException e) {
                System.out.println("Database connection failed.");
                throw e;
            }
        }

        return conn;
    }
}