package ba.stanica.erp.ui;

import ba.stanica.erp.db.Db;
import ba.stanica.erp.db.Repo;
import ba.stanica.erp.db.Repo.Item;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Stanje zaliha (dbo.vw_LagerStanje) i kartica artikla (dbo.usp_KarticaArtikla). */
public class LagerPanel extends JPanel implements Ui.Refreshable {

    private final Ui.DataTable table = new Ui.DataTable("ObjekatId", "ArtikalId");
    private final JComboBox<Item> objekat = Ui.combo(List.of(), "Svi objekti");
    private final JCheckBox ispodMin = new JCheckBox("Samo ispod minimuma");
    private final JTextField search = Ui.field(18, "Traži artikal");
    private final JLabel totals = Ui.muted(" ");
    private final Timer debounce = new Timer(300, e -> refresh());
    private boolean loaded;

    public LagerPanel() {
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        objekat.addActionListener(e -> { if (loaded) refresh(); });
        ispodMin.addActionListener(e -> refresh());
        table.rowColor(r -> Boolean.TRUE.equals(table.value(r, "IspodMinimuma")) ? Ui.DANGER_BG : null);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    kartica();
                }
            }
        });

        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.add(Ui.toolbar(objekat, search, ispodMin, Ui.primary("Kartica artikla", e -> kartica())),
                BorderLayout.NORTH);
        body.add(Ui.scroll(table), BorderLayout.CENTER);
        body.add(totals, BorderLayout.SOUTH);
        Ui.screen(this, Ui.title("Lager",
                "Stanje zaliha po objektu iz view-a dbo.vw_LagerStanje. Crveno = ispod minimalne zalihe. "
                        + "Dupli klik otvara karticu."), body);
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
        Ui.async(this, () -> Repo.lager(Ui.idOrNull(objekat), ispodMin.isSelected(), search.getText()), r -> {
            table.setData(r);
            BigDecimal nab = BigDecimal.ZERO, mp = BigDecimal.ZERO;
            int ispod = 0;
            for (int i = 0; i < r.rows().size(); i++) {
                nab = nab.add(nz(r.get(i, "NabavnaVrijednost")));
                mp = mp.add(nz(r.get(i, "MaloprodajnaVrijednost")));
                if (Boolean.TRUE.equals(r.get(i, "IspodMinimuma"))) {
                    ispod++;
                }
            }
            totals.setText("Stavki: " + r.rows().size() + "   ·   Nabavna vrijednost: " + Ui.km(nab)
                    + "   ·   Maloprodajna vrijednost (pumpe): " + Ui.km(mp) + "   ·   Ispod minimuma: " + ispod);
        });
    }

    private static BigDecimal nz(Object o) {
        return o instanceof BigDecimal b ? b : BigDecimal.ZERO;
    }

    private void kartica() {
        int r = table.selectedModelRow();
        if (r < 0) {
            Ui.info(this, "Izaberite artikal u tabeli.");
            return;
        }
        int obj = ((Number) table.value(r, "ObjekatId")).intValue();
        int art = ((Number) table.value(r, "ArtikalId")).intValue();
        String naslov = table.value(r, "Sifra") + " " + table.value(r, "Naziv") + "  ·  " + table.value(r, "Objekat");
        new KarticaDialog(obj, art, naslov).setVisible(true);
    }

    /** Samo za režim snimanja ekrana (App --snimci): dijalog bez blokiranja. */
    public JDialog karticaZaSnimak(int obj, int art, String naslov) {
        KarticaDialog d = new KarticaDialog(obj, art, naslov);
        d.setModalityType(Dialog.ModalityType.MODELESS);
        return d;
    }

    private class KarticaDialog extends JDialog {
        private final Ui.DataTable kartica = new Ui.DataTable();
        private final JSpinner od = Ui.dateSpinner(LocalDate.now().minusDays(30));
        private final JSpinner doDatuma = Ui.dateSpinner(LocalDate.now());
        private final int obj, art;

        KarticaDialog(int obj, int art, String naslov) {
            super(SwingUtilities.getWindowAncestor(LagerPanel.this), "Kartica artikla", ModalityType.APPLICATION_MODAL);
            this.obj = obj;
            this.art = art;
            kartica.boldRow(r -> r == 0);
            kartica.rowColor(r -> r == 0 ? new Color(0xF2F4F7) : null);

            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
            p.add(Ui.stack(Ui.title(naslov, "Tekući saldo računa window funkcija SUM() OVER (ORDER BY …) "
                            + "u proceduri dbo.usp_KarticaArtikla"),
                    Ui.toolbar(new JLabel("Od"), od, new JLabel("do"), doDatuma, Ui.primary("Prikaži", e -> load()))),
                    BorderLayout.NORTH);
            p.add(Ui.scroll(kartica), BorderLayout.CENTER);
            setContentPane(p);
            setSize(980, 620);
            setLocationRelativeTo(LagerPanel.this);
            load();
        }

        private void load() {
            Ui.async(this, () -> Repo.kartica(obj, art, Ui.date(od), Ui.date(doDatuma)), (Db.Result r) -> kartica.setData(r));
        }
    }
}
