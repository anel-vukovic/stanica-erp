package ba.stanica.erp.db;

import java.io.InputStream;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Tanak sloj iznad JDBC-a: konekcija, parametrizovani upiti i prevod SQL grešaka
 * u poruke za korisnika. Nigdje se SQL ne lijepi iz stringova korisnika:
 * sve vrijednosti idu kao parametri (zaštita od SQL injection-a).
 */
public final class Db {

    private static final Properties CFG = new Properties();

    static {
        try (InputStream in = Db.class.getResourceAsStream("/db.properties")) {
            if (in != null) {
                CFG.load(in);
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Db() {
    }

    /** Rezultat upita: nazivi kolona, Java tipovi kolona i redovi. */
    public record Result(List<String> columns, List<Class<?>> types, List<Object[]> rows) {
        public static final Result EMPTY = new Result(List.of(), List.of(), List.of());

        public int col(String name) {
            return columns.indexOf(name);
        }

        public Object get(int row, String column) {
            int i = col(column);
            return i < 0 ? null : rows.get(row)[i];
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    private static String cfg(String key, String env) {
        String v = System.getenv(env);
        return v != null && !v.isBlank() ? v : CFG.getProperty(key);
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                cfg("db.url", "STANICA_DB_URL"),
                cfg("db.user", "STANICA_DB_USER"),
                cfg("db.password", "STANICA_DB_PASSWORD"));
    }

    public static Result query(String sql, Object... params) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return read(rs);
            }
        }
    }

    /** Poziv procedure koja vraća jedan ili više result setova. */
    public static List<Result> call(String call, Object... params) throws SQLException {
        try (Connection c = connect(); CallableStatement cs = c.prepareCall(call)) {
            bind(cs, params);
            List<Result> out = new ArrayList<>();
            boolean hasResultSet = cs.execute();
            while (true) {
                if (hasResultSet) {
                    try (ResultSet rs = cs.getResultSet()) {
                        out.add(read(rs));
                    }
                } else if (cs.getUpdateCount() == -1) {
                    break;
                }
                hasResultSet = cs.getMoreResults();
            }
            return out;
        }
    }

    static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else if (p instanceof LocalDate d) {
                ps.setDate(i + 1, java.sql.Date.valueOf(d));
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    static Result read(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>(n);
        List<Class<?>> types = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            cols.add(md.getColumnLabel(i));
            Class<?> type;
            try {
                type = Class.forName(md.getColumnClassName(i));
            } catch (ClassNotFoundException e) {
                type = Object.class;
            }
            types.add(type);
        }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[n];
            for (int i = 0; i < n; i++) {
                row[i] = rs.getObject(i + 1);
            }
            rows.add(row);
        }
        return new Result(cols, types, rows);
    }

    /**
     * Greške 50000+ su naše poslovne greške (THROW u procedurama i triggerima),
     * pa se poruka prikazuje korisniku takva kakva jeste.
     */
    public static String friendly(SQLException e) {
        int code = e.getErrorCode();
        if (code >= 50000) {
            return e.getMessage();
        }
        return switch (code) {
            case 547 -> "Zapis je povezan sa drugim podacima, pa se ne može obrisati ili izmijeniti.";
            case 2627, 2601 -> "Zapis sa istom vrijednošću već postoji.";
            case 1205 -> "Baza je bila zauzeta (deadlock). Pokušajte ponovo.";
            case 18456 -> "Prijava na SQL Server nije uspjela. Provjerite korisnika i lozinku u db.properties.";
            case 0 -> "Nema veze sa SQL Serverom. Pokrenite ga sa scripts/db-start.sh.\n\n" + e.getMessage();
            default -> "Greška baze (" + code + "): " + e.getMessage();
        };
    }

    public static String serverInfo() {
        try (Connection c = connect()) {
            DatabaseMetaData m = c.getMetaData();
            return "Povezano: " + m.getDatabaseProductName() + " " + m.getDatabaseProductVersion()
                    + "  ·  baza " + c.getCatalog() + "  ·  korisnik " + m.getUserName();
        } catch (SQLException e) {
            return "Nema veze sa SQL Serverom (" + e.getMessage() + ")";
        }
    }
}
