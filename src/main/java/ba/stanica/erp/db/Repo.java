package ba.stanica.erp.db;

import ba.stanica.erp.db.Db.Result;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sav pristup podacima na jednom mjestu (repository).
 * UI klase ne znaju ništa o SQL-u; čitanje ide preko view-ova,
 * a svaka izmjena preko stored procedura koje provode poslovna pravila.
 */
public final class Repo {

    private Repo() {
    }

    /** Stavka za combo box: id + tekst. id = -1 znači "svi". */
    public record Item(int id, String naziv) {
        @Override
        public String toString() {
            return naziv;
        }
    }

    public record NoviDokument(int id, String broj) {
    }

    public record Nivelacija(BigDecimal razlika, int brojDokumenata) {
    }

    // ---------------------------------------------------------------- šifarnici

    /** @param tip "MP", "VP" ili null za sve objekte */
    public static List<Item> objekti(String tip) throws SQLException {
        Result r = tip == null
                ? Db.query("SELECT ObjekatId, Naziv FROM dbo.Objekat ORDER BY Sifra")
                : Db.query("SELECT ObjekatId, Naziv FROM dbo.Objekat WHERE Tip = ? ORDER BY Sifra", tip);
        return items(r);
    }

    /** @param tipovi npr. "D", "O" za dobavljače */
    public static List<Item> partneri(String... tipovi) throws SQLException {
        String in = String.join(",", Collections.nCopies(tipovi.length, "?"));
        return items(Db.query("SELECT PartnerId, Naziv FROM dbo.Partner WHERE Tip IN (" + in + ") ORDER BY Naziv",
                (Object[]) tipovi));
    }

    public static List<Item> grupe() throws SQLException {
        return items(Db.query("SELECT GrupaId, Naziv FROM dbo.Grupa ORDER BY GrupaId"));
    }

    public static List<Item> artikli() throws SQLException {
        return items(Db.query(
                "SELECT ArtikalId, Sifra + N' - ' + Naziv FROM dbo.Artikal WHERE Aktivan = 1 ORDER BY Sifra"));
    }

    private static List<Item> items(Result r) {
        List<Item> out = new ArrayList<>();
        for (Object[] row : r.rows()) {
            out.add(new Item(((Number) row[0]).intValue(), String.valueOf(row[1])));
        }
        return out;
    }

    // ---------------------------------------------------------------- artikli

    public static Result artikliTabela(String filter, boolean iNeaktivni) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT a.ArtikalId, a.GrupaId, a.Sifra, a.Naziv, g.Naziv AS Grupa, a.JedMjere AS JM,
                       a.PdvStopa, a.MinZaliha, a.Aktivan
                FROM dbo.Artikal AS a
                JOIN dbo.Grupa AS g ON g.GrupaId = a.GrupaId
                WHERE 1 = 1""");
        List<Object> p = new ArrayList<>();
        if (!iNeaktivni) {
            sql.append(" AND a.Aktivan = 1");
        }
        if (filter != null && !filter.isBlank()) {
            sql.append(" AND (a.Sifra LIKE ? OR a.Naziv LIKE ?)");
            String f = "%" + filter.trim() + "%";
            p.add(f);
            p.add(f);
        }
        sql.append(" ORDER BY a.Sifra");
        return Db.query(sql.toString(), p.toArray());
    }

    public static int sacuvajArtikal(Integer id, String sifra, String naziv, int grupaId, String jm,
                                     BigDecimal pdv, BigDecimal minZaliha, boolean aktivan, BigDecimal mpc)
            throws SQLException {
        try (Connection c = Db.connect();
             CallableStatement cs = c.prepareCall("{call dbo.usp_Artikal_Sacuvaj(?,?,?,?,?,?,?,?,?)}")) {
            if (id == null) {
                cs.setNull(1, Types.INTEGER);
            } else {
                cs.setInt(1, id);
            }
            cs.registerOutParameter(1, Types.INTEGER);
            cs.setString(2, sifra);
            cs.setString(3, naziv);
            cs.setInt(4, grupaId);
            cs.setString(5, jm);
            cs.setBigDecimal(6, pdv);
            cs.setBigDecimal(7, minZaliha);
            cs.setBoolean(8, aktivan);
            if (mpc == null) {
                cs.setNull(9, Types.DECIMAL);
            } else {
                cs.setBigDecimal(9, mpc);
            }
            cs.execute();
            return cs.getInt(1);
        }
    }

    // ---------------------------------------------------------------- lager

    public static Result lager(Integer objekatId, boolean samoIspodMinimuma, String filter) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT ObjekatId, ArtikalId, Objekat, Sifra, Naziv, Grupa, JedMjere AS JM, Kolicina, MinZaliha,
                       ProsjNabCijena, NabavnaVrijednost, MPC, MaloprodajnaVrijednost, IspodMinimuma
                FROM dbo.vw_LagerStanje
                WHERE 1 = 1""");
        List<Object> p = new ArrayList<>();
        if (objekatId != null) {
            sql.append(" AND ObjekatId = ?");
            p.add(objekatId);
        }
        if (samoIspodMinimuma) {
            sql.append(" AND IspodMinimuma = 1");
        }
        if (filter != null && !filter.isBlank()) {
            sql.append(" AND (Sifra LIKE ? OR Naziv LIKE ?)");
            String f = "%" + filter.trim() + "%";
            p.add(f);
            p.add(f);
        }
        sql.append(" ORDER BY ObjekatSifra, Sifra");
        return Db.query(sql.toString(), p.toArray());
    }

    public static Result kartica(int objekatId, int artikalId, LocalDate od, LocalDate doDatuma) throws SQLException {
        return Db.call("{call dbo.usp_KarticaArtikla(?,?,?,?)}", objekatId, artikalId, od, doDatuma).get(0);
    }

    // ---------------------------------------------------------------- dokumenti

    public static Result dokumenti(String vrsta, Integer objekatId, LocalDate od, LocalDate doDatuma)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT DokumentId, StatusKod, ObjekatId, Broj, Datum, Objekat, Partner, Status, Stavki,
                       IznosBezPdv, Pdv, IznosSaPdv, Napomena
                FROM dbo.vw_DokumentPregled
                WHERE Datum BETWEEN ? AND ?""");
        List<Object> p = new ArrayList<>(List.of(od, doDatuma));
        if (vrsta != null) {
            sql.append(" AND Vrsta = ?");
            p.add(vrsta);
        }
        if (objekatId != null) {
            sql.append(" AND ObjekatId = ?");
            p.add(objekatId);
        }
        sql.append(" ORDER BY Datum DESC, DokumentId DESC");
        return Db.query(sql.toString(), p.toArray());
    }

    public static Result zaglavlje(int dokumentId) throws SQLException {
        return Db.query("""
                SELECT d.DokumentId, d.Vrsta, d.Broj, d.Datum, d.Status, d.Napomena,
                       o.Naziv AS Objekat, o.Grad AS ObjekatGrad,
                       p.Naziv AS Partner, p.JIB, p.Grad AS PartnerGrad
                FROM dbo.Dokument AS d
                JOIN dbo.Objekat AS o ON o.ObjekatId = d.ObjekatId
                LEFT JOIN dbo.Partner AS p ON p.PartnerId = d.PartnerId
                WHERE d.DokumentId = ?""", dokumentId);
    }

    public static Result stavke(int dokumentId) throws SQLException {
        return Db.query("""
                SELECT v.StavkaId, a.Sifra, a.Naziv AS Artikal, a.JedMjere AS JM, v.Kolicina, v.Cijena,
                       v.RabatPosto, v.PdvStopa, v.IznosBezPdv, v.IznosPdv, v.IznosSaPdv
                FROM dbo.vw_StavkaIznos AS v
                JOIN dbo.Artikal AS a ON a.ArtikalId = v.ArtikalId
                WHERE v.DokumentId = ?
                ORDER BY v.StavkaId""", dokumentId);
    }

    public static NoviDokument kreirajDokument(String vrsta, LocalDate datum, int objekatId, Integer partnerId,
                                               String napomena) throws SQLException {
        try (Connection c = Db.connect();
             CallableStatement cs = c.prepareCall("{call dbo.usp_Dokument_Kreiraj(?,?,?,?,?,?,?)}")) {
            cs.setString(1, vrsta);
            cs.setDate(2, java.sql.Date.valueOf(datum));
            cs.setInt(3, objekatId);
            if (partnerId == null) {
                cs.setNull(4, Types.INTEGER);
            } else {
                cs.setInt(4, partnerId);
            }
            cs.setString(5, napomena == null || napomena.isBlank() ? null : napomena.trim());
            cs.registerOutParameter(6, Types.INTEGER);
            cs.registerOutParameter(7, Types.NVARCHAR);
            cs.execute();
            return new NoviDokument(cs.getInt(6), cs.getString(7));
        }
    }

    /** cijena i rabat mogu biti null: procedura ih tada sama povuče (MPC, VPC, rabat kupca). */
    public static void dodajStavku(int dokumentId, int artikalId, BigDecimal kolicina, BigDecimal cijena,
                                   BigDecimal rabat) throws SQLException {
        Db.call("{call dbo.usp_Stavka_Dodaj(?,?,?,?,?)}", dokumentId, artikalId, kolicina, cijena, rabat);
    }

    public static void obrisiStavku(int stavkaId) throws SQLException {
        Db.call("{call dbo.usp_Stavka_Obrisi(?)}", stavkaId);
    }

    public static void obrisiDokument(int dokumentId) throws SQLException {
        Db.call("{call dbo.usp_Dokument_Obrisi(?)}", dokumentId);
    }

    public static void proknjizi(int dokumentId) throws SQLException {
        Db.call("{call dbo.usp_Dokument_Proknjizi(?)}", dokumentId);
    }

    // ---------------------------------------------------------------- nivelacija

    /** @param objekatId null = sve pumpe */
    public static Nivelacija nivelacija(int artikalId, BigDecimal novaMpc, Integer objekatId) throws SQLException {
        try (Connection c = Db.connect();
             CallableStatement cs = c.prepareCall("{call dbo.usp_Nivelacija(?,?,?,?,?,?)}")) {
            cs.setInt(1, artikalId);
            cs.setBigDecimal(2, novaMpc);
            if (objekatId == null) {
                cs.setNull(3, Types.INTEGER);
            } else {
                cs.setInt(3, objekatId);
            }
            cs.setNull(4, Types.DATE);
            cs.registerOutParameter(5, Types.DECIMAL, 2);
            cs.registerOutParameter(6, Types.INTEGER);
            cs.execute();
            return new Nivelacija(cs.getBigDecimal(5), cs.getInt(6));
        }
    }

    public static Result cijenePoPumpama(int artikalId) throws SQLException {
        return Db.query("""
                SELECT o.Naziv AS Objekat, c.MPC, ISNULL(l.Kolicina, 0) AS Kolicina, c.VaziOd
                FROM dbo.Objekat AS o
                JOIN dbo.Cijena AS c ON c.ObjekatId = o.ObjekatId AND c.ArtikalId = ?
                LEFT JOIN dbo.Lager AS l ON l.ObjekatId = o.ObjekatId AND l.ArtikalId = c.ArtikalId
                WHERE o.Tip = 'MP'
                ORDER BY o.Sifra""", artikalId);
    }

    public static Result historijaCijena(int artikalId) throws SQLException {
        return Db.query("""
                SELECT TOP (200) h.Promijenjeno, o.Naziv AS Objekat, h.StaraMPC, h.NovaMPC,
                       CAST(h.NovaMPC - h.StaraMPC AS DECIMAL(18,4)) AS Promjena, h.Korisnik
                FROM dbo.CijenaHistorija AS h
                JOIN dbo.Objekat AS o ON o.ObjekatId = h.ObjekatId
                WHERE h.ArtikalId = ? AND h.StaraMPC IS NOT NULL
                ORDER BY h.HistorijaId DESC""", artikalId);
    }

    // ---------------------------------------------------------------- izvještaji

    public static Result prodaja(LocalDate od, LocalDate doDatuma, Integer objekatId) throws SQLException {
        return Db.call("{call dbo.usp_Izvjestaj_Prodaja(?,?,?)}", od, doDatuma, objekatId).get(0);
    }

    /** [0] KPI, [1] promet po danima, [2] artikli ispod minimuma */
    public static List<Result> pregled() throws SQLException {
        return Db.call("{call dbo.usp_Pregled}");
    }
}
