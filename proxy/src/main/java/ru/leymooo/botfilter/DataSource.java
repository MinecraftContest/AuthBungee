package ru.leymooo.botfilter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import ru.leymooo.botfilter.config.Settings;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class DataSource {

    private static Properties props = new Properties();
    private static HikariDataSource ds;

    static {

        Properties props = new Properties();

        PGSimpleDataSource d;

        props.setProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource");
        props.setProperty("dataSource.user", Settings.IMP.AUTH_DB.DB_USER);
        props.setProperty("dataSource.password", Settings.IMP.AUTH_DB.DB_PASSWORD);
        props.setProperty("dataSource.databaseName", Settings.IMP.AUTH_DB.DB_NAME);
        props.put("dataSource.portNumber", Settings.IMP.AUTH_DB.DB_PORT);
        props.put("dataSource.serverName", Settings.IMP.AUTH_DB.DB_HOST);
        props.put("dataSource.logWriter", new PrintWriter(System.out));

        HikariConfig config = new HikariConfig(props);
        ds = new HikariDataSource(config);
    }

    private DataSource() {
    }

    public static Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

}