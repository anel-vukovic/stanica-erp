package ba.stanica.erp.ui;

import ba.stanica.erp.db.Db;
import ba.stanica.erp.db.Repo;
import ba.stanica.erp.db.Repo.Item;
import ba.stanica.erp.report.Pdf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Dokumenti: kalkulacije (ulaz), maloprodajni računi, veleprodajne fakture i nivelacije.
 * Knjiženje radi procedura dbo.usp_Dokument_Proknjizi u jednoj transakciji.
 */
public class DokumentiPanel extends JPanel implements Ui.Refreshable {

    record Vrsta(String kod, String naziv) {
        @Override
        public String toString() {
            return naziv;
        }
    }

    private static final Vrsta[] VRSTE = {
            new Vrsta(null, "Sve vrste"),
            new Vrsta("KAL", "Kalkulacije (ulaz robe)"),
            new Vrsta("RAC", "Maloprodajni računi"),
            new Vrsta("FAK", "Veleprodajne fakture"),
            new Vrsta("NIV", "Nivelacije cijena")};

    private final Ui.DataTable docs = new Ui.DataTable("DokumentId", "StatusKod", "ObjekatId");
    private final Ui.DataTable stavke = new Ui.DataTable("StavkaId");
    private final JComboBox<Vrsta> vrsta = new JComboBox<>(VRSTE);
    private final JComboBox<Item> objekat = Ui.combo(List.of(), "Svi objekti");
    private final JSpinner od = Ui.dateSpinner(LocalDate.now().minusDays(14));
    private final JSpinner doDatuma = Ui.dateSpinner(LocalDate.now());
    private final JLabel stavkeNaslov = Ui.muted("Izaberite dokument da vidite stavke.");
    private boolean loaded;

    public DokumentiPanel() {
        docs.rowColor(r -> "O".equals(docs.value(r, "StatusKod")) ? Ui.WARN_BG : null);
        docs.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ucitajStavke();
            }
        });
        docs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    otvori();
                }
            }
        });
        vrsta.addActionListener(e -> refresh());
        objekat.addActionListener(e -> { if (loaded) refresh(); });

        JPanel filters = Ui.toolbar(vrsta, objekat, new JLabel("Od"), od, new JLabel("do"), doDatuma,
                Ui.button("Prikaži", e -> refresh()));
        JPanel actions = Ui.toolbar(Ui.primary("Novi dokument", e -> novi()), Ui.button("Otvori", e -> otvori()),
                Ui.button("Proknjiži", e -> proknjizi()), Ui.button("Obriši", e -> obrisi()),
                Ui.button("PDF dokumenta", e -> pdf()));

        JPanel lower = new JPanel(new BorderLayout(0, 4));
        lower.add(stavkeNaslov, BorderLayout.NORTH);
        lower.add(Ui.scroll(stavke), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(docs), lower);
        split.setResizeWeight(0.62);
        split.setBorder(null);

        JPanel body = new JPanel(new BorderLayout());
        body.add(Ui.stack(filters, actions), BorderLayout.NORTH);
        body.add(split, BorderLayout.CENTER);
        Ui.screen(this, Ui.title("Dokumenti",
                "Žuto = otvoren dokument. Knjiženje provjerava zalihu, skida lager i piše karticu u jednoj transakciji."),
                body);
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
        Vrsta v = (Vrsta) vrsta.getSelectedItem();
        Ui.async(this, () -> Repo.dokumenti(v.kod(), Ui.idOrNull(objekat), Ui.date(od), Ui.date(doDatuma)), r -> {
            docs.setData(r);
            stavke.setData(Db.Result.EMPTY);
            stavkeNaslov.setText(r.rows().size() + " dokumenata. Izaberite dokument da vidite stavke.");
        });
    }

    private int selectedId() {
        int r = docs.selectedModelRow();
        return r < 0 ? -1 : ((Number) docs.value(r, "DokumentId")).intValue();
    }

    private void ucitajStavke() {
        int r = docs.selectedModelRow();
        if (r < 0) {
            return;
        }
        int id = ((Number) docs.value(r, "DokumentId")).intValue();
        String broj = (String) docs.value(r, "Broj");
        Ui.async(this, () -> Repo.stavke(id), s -> {
            stavke.setData(s);
            stavkeNaslov.setText("Stavke dokumenta " + broj + "  ·  ukupno sa PDV-om: "
                    + Ui.km(docs.value(r, "IznosSaPdv")));
        });
    }

    private void novi() {
        new DokumentDialog(null).setVisible(true);
        refresh();
    }

    private void otvori() {
        int id = selectedId();
        if (id < 0) {
            Ui.info(this, "Izaberite dokument.");
            return;
        }
        new DokumentDialog(id).setVisible(true);
        refresh();
    }

    private void proknjizi() {
        int r = docs.selectedModelRow();
        if (r < 0) {
            Ui.info(this, "Izaberite dokument.");
            return;
        }
        String broj = (String) docs.value(r, "Broj");
        if (!"O".equals(docs.value(r, "StatusKod"))) {
            Ui.info(this, "Dokument " + broj + " je već proknjižen.");
            return;
        }
        if (!Ui.confirm(this, "Proknjižiti dokument " + broj + "?\nNakon knjiženja dokument se više ne može mijenjati.")) {
            return;
        }
        int id = selectedId();
        Ui.async(this, () -> { Repo.proknjizi(id); return null; }, x -> {
            Ui.info(this, "Dokument " + broj + " je proknjižen. Lager i kartica artikla su ažurirani.");
            refresh();
        });
    }

    private void obrisi() {
        int r = docs.selectedModelRow();
        if (r < 0) {
            Ui.info(this, "Izaberite dokument.");
            return;
        }
        String broj = (String) docs.value(r, "Broj");
        if (!Ui.confirm(this, "Obrisati dokument " + broj + "?")) {
            return;
        }
        int id = selectedId();
        // Za proknjižen dokument trigger trg_Dokument_Zastita vraća grešku i ništa se ne briše
        Ui.async(this, () -> { Repo.obrisiDokument(id); return null; }, x -> refresh());
    }

    private void pdf() {
        int id = selectedId();
        if (id < 0) {
            Ui.info(this, "Izaberite dokument.");
            return;
        }
        String broj = (String) docs.value(docs.selectedModelRow(), "Broj");
        File f = Ui.savePdf(this, broj + ".pdf");
        if (f == null) {
            return;
        }
        Ui.async(this, () -> {
            Pdf.dokument(f, Repo.zaglavlje(id), Repo.stavke(id));
            return f;
        }, file -> Ui.open(this, file));
    }

    /** Samo za režim snimanja ekrana (App --snimci): dijalog bez blokiranja. */
    public JDialog dialogZaSnimak(Integer dokumentId) {
        DokumentDialog d = new DokumentDialog(dokumentId);
        d.setModalityType(Dialog.ModalityType.MODELESS);
        return d;
    }

    // ===================================================================== editor dokumenta

    private class DokumentDialog extends JDialog {
        private Integer dokId;
        private boolean proknjizen;

        // zaglavlje (samo za novi dokument)
        private final JComboBox<Vrsta> dVrsta = new JComboBox<>(new Vrsta[]{VRSTE[1], VRSTE[2], VRSTE[3]});
        private final JComboBox<Item> dObjekat = new JComboBox<>();
        private final JComboBox<Item> dPartner = new JComboBox<>();
        private final JSpinner dDatum = Ui.dateSpinner(LocalDate.now());
        private final JTextField dNapomena = Ui.field(24, "opcionalno");
        private final JPanel header = new JPanel(new BorderLayout());
        private JPanel formWrap;
        private final JLabel summary = new JLabel();

        // stavke
        private final JComboBox<Item> artikal = new JComboBox<>();
        private final JTextField kolicina = Ui.field(8, "količina");
        private final JTextField cijena = Ui.field(8, "auto");
        private final JTextField rabat = Ui.field(5, "auto");
        private final JButton dodaj = Ui.primary("Dodaj stavku", e -> dodajStavku());
        private final JButton ukloni = Ui.button("Ukloni stavku", e -> ukloniStavku());
        private final JButton knjizi = Ui.primary("Proknjiži dokument", e -> proknjiziOvaj());
        private final Ui.DataTable tabela = new Ui.DataTable("StavkaId");
        private final JLabel ukupno = new JLabel(" ");
        private final JLabel cijenaHint = Ui.muted(" ");

        DokumentDialog(Integer id) {
            super(SwingUtilities.getWindowAncestor(DokumentiPanel.this), "Dokument", ModalityType.APPLICATION_MODAL);
            this.dokId = id;

            JPanel form = Ui.form("Vrsta", dVrsta, "Objekat", dObjekat, "Partner", dPartner, "Datum", dDatum,
                    "Napomena", dNapomena);
            formWrap = new JPanel(new BorderLayout());
            formWrap.add(form, BorderLayout.WEST);
            JPanel createBtn = new JPanel(new FlowLayout(FlowLayout.LEFT));
            createBtn.add(Ui.primary("Kreiraj dokument", e -> kreiraj()));
            formWrap.add(createBtn, BorderLayout.SOUTH);
            summary.putClientProperty("FlatLaf.styleClass", "h3");
            dVrsta.addActionListener(e -> ucitajZaglavljeListe());

            ukupno.putClientProperty("FlatLaf.styleClass", "h4");
            JPanel itemBar = Ui.toolbar(new JLabel("Artikal"), artikal, kolicina, new JLabel("Cijena"), cijena,
                    new JLabel("Rabat %"), rabat, dodaj, ukloni);
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.add(ukupno, BorderLayout.WEST);
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btns.add(Ui.button("Zatvori", e -> dispose()));
            btns.add(knjizi);
            bottom.add(btns, BorderLayout.EAST);

            JPanel center = new JPanel(new BorderLayout(0, 4));
            center.add(Ui.stack(itemBar, cijenaHint), BorderLayout.NORTH);
            center.add(Ui.scroll(tabela), BorderLayout.CENTER);

            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.setBorder(new EmptyBorder(14, 16, 12, 16));
            p.add(header, BorderLayout.NORTH);
            p.add(center, BorderLayout.CENTER);
            p.add(bottom, BorderLayout.SOUTH);
            setContentPane(p);
            setSize(1080, 640);
            setLocationRelativeTo(DokumentiPanel.this);

            Ui.async(this, Repo::artikli, list -> Ui.fill(artikal, list, null));
            if (dokId == null) {
                setTitle("Novi dokument");
                header.add(formWrap, BorderLayout.CENTER);
                ucitajZaglavljeListe();
                setStavkeEnabled(false);
            } else {
                ucitajPostojeci();
            }
        }

        /** Objekti i partneri zavise od vrste dokumenta (pravila su ista kao u proceduri). */
        private void ucitajZaglavljeListe() {
            String v = ((Vrsta) dVrsta.getSelectedItem()).kod();
            String tipObjekta = switch (v) {
                case "RAC" -> "MP";
                case "FAK" -> "VP";
                default -> null;
            };
            Ui.async(this, () -> new Object[]{
                    Repo.objekti(tipObjekta),
                    v.equals("KAL") ? Repo.partneri("D", "O") : v.equals("FAK") ? Repo.partneri("K", "O") : List.<Item>of()
            }, (Object[] res) -> {
                @SuppressWarnings("unchecked") List<Item> o = (List<Item>) res[0];
                @SuppressWarnings("unchecked") List<Item> pa = (List<Item>) res[1];
                Ui.fill(dObjekat, o, null);
                Ui.fill(dPartner, pa, null);
                dPartner.setEnabled(!pa.isEmpty());
                cijenaHint.setText(switch (v) {
                    case "KAL" -> "Kalkulacija: unesite nabavnu cijenu bez PDV-a. Prosječna nabavna cijena se preračunava pri knjiženju.";
                    case "RAC" -> "Račun: prazna cijena = važeća MPC na toj pumpi (iz tabele Cijena).";
                    default -> "Faktura: prazna cijena = prosječna nabavna + 8 %, prazan rabat = ugovoreni rabat kupca.";
                });
            });
        }

        private void kreiraj() {
            Vrsta v = (Vrsta) dVrsta.getSelectedItem();
            Item o = (Item) dObjekat.getSelectedItem();
            Item pa = (Item) dPartner.getSelectedItem();
            if (o == null) {
                Ui.info(this, "Izaberite objekat.");
                return;
            }
            Integer partnerId = dPartner.isEnabled() && pa != null ? pa.id() : null;
            Ui.async(this, () -> Repo.kreirajDokument(v.kod(), Ui.date(dDatum), o.id(), partnerId, dNapomena.getText()),
                    nd -> {
                        dokId = nd.id();
                        ucitajPostojeci();
                    });
        }

        private void ucitajPostojeci() {
            Ui.async(this, () -> Repo.zaglavlje(dokId), z -> {
                String vr = (String) z.get(0, "Vrsta");
                proknjizen = "P".equals(z.get(0, "Status"));
                String partner = z.get(0, "Partner") == null ? "" : "  ·  " + z.get(0, "Partner");
                summary.setText("<html><b>" + z.get(0, "Broj") + "</b>  ·  " + Ui.format("Datum", z.get(0, "Datum"))
                        + "  ·  " + z.get(0, "Objekat") + partner + "  ·  "
                        + (proknjizen ? "<font color='#0F6E56'>Proknjižen</font>" : "<font color='#B54708'>Otvoren</font>")
                        + "</html>");
                setTitle("Dokument " + z.get(0, "Broj"));
                header.removeAll();
                header.add(summary, BorderLayout.CENTER);
                header.revalidate();
                header.repaint();
                cijenaHint.setText(switch (vr) {
                    case "KAL" -> "Kalkulacija: unesite nabavnu cijenu bez PDV-a.";
                    case "RAC" -> "Račun: prazna cijena = važeća MPC na toj pumpi.";
                    case "FAK" -> "Faktura: prazna cijena = prosječna nabavna + 8 %, prazan rabat = rabat kupca.";
                    default -> "Nivelacija se radi na ekranu Nivelacija cijena.";
                });
                setStavkeEnabled(!proknjizen && !"NIV".equals(vr));
                ucitajStavke();
            });
        }

        private void setStavkeEnabled(boolean on) {
            for (JComponent c : new JComponent[]{artikal, kolicina, cijena, rabat, dodaj, ukloni, knjizi}) {
                c.setEnabled(on);
            }
        }

        private void ucitajStavke() {
            if (dokId == null) {
                return;
            }
            Ui.async(this, () -> Repo.stavke(dokId), s -> {
                tabela.setData(s);
                BigDecimal bez = BigDecimal.ZERO, pdv = BigDecimal.ZERO, sa = BigDecimal.ZERO;
                for (int i = 0; i < s.rows().size(); i++) {
                    bez = bez.add((BigDecimal) s.get(i, "IznosBezPdv"));
                    pdv = pdv.add((BigDecimal) s.get(i, "IznosPdv"));
                    sa = sa.add((BigDecimal) s.get(i, "IznosSaPdv"));
                }
                ukupno.setText("Bez PDV: " + Ui.km(bez) + "    PDV: " + Ui.km(pdv) + "    Ukupno: " + Ui.km(sa));
            });
        }

        private void dodajStavku() {
            Item a = (Item) artikal.getSelectedItem();
            BigDecimal kol, cij, rab;
            try {
                kol = Ui.decimal(kolicina, "Količina", true);
                cij = Ui.decimal(cijena, "Cijena", false);
                rab = Ui.decimal(rabat, "Rabat", false);
            } catch (IllegalArgumentException ex) {
                Ui.info(this, ex.getMessage());
                return;
            }
            if (a == null) {
                return;
            }
            Ui.async(this, () -> { Repo.dodajStavku(dokId, a.id(), kol, cij, rab); return null; }, x -> {
                kolicina.setText("");
                cijena.setText("");
                rabat.setText("");
                kolicina.requestFocusInWindow();
                ucitajStavke();
            });
        }

        private void ukloniStavku() {
            int r = tabela.selectedModelRow();
            if (r < 0) {
                Ui.info(this, "Izaberite stavku.");
                return;
            }
            int sid = ((Number) tabela.value(r, "StavkaId")).intValue();
            Ui.async(this, () -> { Repo.obrisiStavku(sid); return null; }, x -> ucitajStavke());
        }

        private void proknjiziOvaj() {
            if (dokId == null) {
                return;
            }
            Ui.async(this, () -> { Repo.proknjizi(dokId); return null; }, x -> {
                Ui.info(this, "Dokument je proknjižen. Lager i kartica artikla su ažurirani.");
                ucitajPostojeci();
            });
        }
    }
}
