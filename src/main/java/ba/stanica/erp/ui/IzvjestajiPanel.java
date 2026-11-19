package ba.stanica.erp.ui;

import ba.stanica.erp.db.Db;
import ba.stanica.erp.db.Repo;
import ba.stanica.erp.db.Repo.Item;
import ba.stanica.erp.report.Pdf;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.List;

/** Izvještaj prodaje sa razlikom u cijeni (dbo.usp_Izvjestaj_Prodaja), izvoz u PDF i štampa. */
public class IzvjestajiPanel extends JPanel implements Ui.Refreshable {

    private final Ui.DataTable table = new Ui.DataTable();
    private final JSpinner od = Ui.dateSpinner(LocalDate.now().withDayOfMonth(1));
    private final JSpinner doDatuma = Ui.dateSpinner(LocalDate.now());
    private final JComboBox<Item> objekat = Ui.combo(List.of(), "Svi objekti");
    private final JLabel summary = new JLabel(" ");
    private boolean loaded;

    public IzvjestajiPanel() {
        table.boldRow(r -> "UKUPNO".equals(table.value(r, "Grupa")));
        table.rowColor(r -> "UKUPNO".equals(table.value(r, "Grupa")) ? new Color(0xE8F3EF) : null);
        summary.putClientProperty("FlatLaf.styleClass", "h4");

        JPanel bar = Ui.toolbar(new JLabel("Od"), od, new JLabel("do"), doDatuma, objekat,
                Ui.primary("Prikaži", e -> refresh()),
                Ui.button("Ovaj mjesec", e -> period(LocalDate.now().withDayOfMonth(1))),
                Ui.button("Zadnjih 7 dana", e -> period(LocalDate.now().minusDays(6))),
                Ui.button("Izvoz u PDF", e -> pdf()), Ui.button("Štampaj", e -> stampaj()));

        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.add(bar, BorderLayout.NORTH);
        body.add(Ui.scroll(table), BorderLayout.CENTER);
        body.add(summary, BorderLayout.SOUTH);
        Ui.screen(this, Ui.title("Izvještaj prodaje",
                "Prodaja po artiklima sa RUC-om. Red UKUPNO računa SQL Server preko GROUP BY GROUPING SETS."), body);
    }

    private void period(LocalDate from) {
        od.setValue(java.util.Date.from(from.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
        doDatuma.setValue(new java.util.Date());
        refresh();
    }

    @Override
    public void refresh() {
        if (!loaded) {
            Ui.async(this, () -> Repo.objekti(null), list -> {
                Ui.fill(objekat, list, "Svi objekti");
                loaded = true;
                refresh();
            });
            return;
        }
        Ui.async(this, () -> Repo.prodaja(Ui.date(od), Ui.date(doDatuma), Ui.idOrNull(objekat)), r -> {
            table.setData(r);
            int last = r.rows().size() - 1;
            if (last >= 0 && "UKUPNO".equals(r.get(last, "Grupa"))) {
                summary.setText("Prodaja sa PDV: " + Ui.km(r.get(last, "ProdajaSaPdv"))
                        + "     RUC: " + Ui.km(r.get(last, "RUC"))
                        + "     RUC %: " + Ui.format("RucPosto", r.get(last, "RucPosto")) + " %");
            } else {
                summary.setText("Nema prodaje u izabranom periodu.");
            }
        });
    }

    private String podnaslov() {
        return "Period: " + Ui.date(od).format(Ui.DATUM) + " – " + Ui.date(doDatuma).format(Ui.DATUM)
                + "   ·   Objekat: " + objekat.getSelectedItem();
    }

    private void pdf() {
        Db.Result r = table.result();
        if (r.isEmpty()) {
            Ui.info(this, "Prvo prikažite izvještaj.");
            return;
        }
        File f = Ui.savePdf(this, "Prodaja_" + Ui.date(od) + "_" + Ui.date(doDatuma) + ".pdf");
        if (f == null) {
            return;
        }
        String sub = podnaslov();
        Ui.async(this, () -> {
            Pdf.izvjestaj(f, "Izvještaj prodaje sa razlikom u cijeni", sub, r,
                    row -> "UKUPNO".equals(row[r.col("Grupa")]));
            return f;
        }, file -> Ui.open(this, file));
    }

    private void stampaj() {
        try {
            table.print(JTable.PrintMode.FIT_WIDTH,
                    new MessageFormat("Izvještaj prodaje  " + podnaslov().replace("'", "''")),
                    new MessageFormat("Strana {0}"));
        } catch (Exception e) {
            Ui.error(this, e);
        }
    }
}
