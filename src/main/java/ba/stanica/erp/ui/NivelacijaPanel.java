package ba.stanica.erp.ui;

import ba.stanica.erp.db.Repo;
import ba.stanica.erp.db.Repo.Item;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;

/** Promjena maloprodajne cijene (dbo.usp_Nivelacija) i revizijski trag (trigger trg_Cijena_Historija). */
public class NivelacijaPanel extends JPanel implements Ui.Refreshable {

    private final JComboBox<Item> artikal = new JComboBox<>();
    private final JComboBox<Item> objekat = Ui.combo(List.of(), "Sve pumpe");
    private final JTextField novaMpc = Ui.field(10, "npr. 2,75");
    private final Ui.DataTable cijene = new Ui.DataTable();
    private final Ui.DataTable historija = new Ui.DataTable();
    private boolean loaded;

    public NivelacijaPanel() {
        artikal.addActionListener(e -> { if (loaded) ucitaj(); });
        historija.rowColor(r -> {
            Object p = historija.value(r, "Promjena");
            return p instanceof BigDecimal b && b.signum() > 0 ? new Color(0xFFF1F0)
                    : p instanceof BigDecimal b2 && b2.signum() < 0 ? new Color(0xECFDF3) : null;
        });

        JPanel form = Ui.form("Artikal", artikal, "Pumpa", objekat, "Nova MPC (KM, sa PDV)", novaMpc);
        JPanel formBtn = Ui.toolbar(Ui.primary("Provedi nivelaciju", e -> provedi()));
        JPanel left = Ui.card("Nova cijena", Ui.stack(form, formBtn,
                Ui.muted("<html>Za svaku pumpu nastaje dokument NIV. Razlika u vrijednosti zaliha<br>"
                        + "= količina na stanju × (nova − stara MPC).</html>")));
        JPanel leftCol = new JPanel(new BorderLayout(0, 12));
        leftCol.add(left, BorderLayout.NORTH);
        leftCol.add(Ui.card("Trenutne cijene po pumpama", Ui.scroll(cijene)), BorderLayout.CENTER);

        JPanel right = Ui.card("Historija cijena (puni je trigger dbo.trg_Cijena_Historija)", Ui.scroll(historija));

        JPanel body = new JPanel(new GridLayout(1, 2, 14, 0));
        body.add(leftCol);
        body.add(right);
        Ui.screen(this, Ui.title("Nivelacija cijena",
                "Cijene goriva se mijenjaju često. Promjena ide kroz dokument, a svaka promjena ostaje zabilježena."),
                body);
    }

    @Override
    public void refresh() {
        if (!loaded) {
            Ui.async(this, () -> new Object[]{Repo.artikli(), Repo.objekti("MP")}, (Object[] r) -> {
                @SuppressWarnings("unchecked") List<Item> a = (List<Item>) r[0];
                @SuppressWarnings("unchecked") List<Item> o = (List<Item>) r[1];
                Ui.fill(artikal, a, null);
                Ui.fill(objekat, o, "Sve pumpe");
                loaded = true;
                ucitaj();
            });
            return;
        }
        ucitaj();
    }

    private void ucitaj() {
        Item a = (Item) artikal.getSelectedItem();
        if (a == null) {
            return;
        }
        Ui.async(this, () -> new Object[]{Repo.cijenePoPumpama(a.id()), Repo.historijaCijena(a.id())},
                (Object[] r) -> {
                    cijene.setData((ba.stanica.erp.db.Db.Result) r[0]);
                    historija.setData((ba.stanica.erp.db.Db.Result) r[1]);
                });
    }

    private void provedi() {
        Item a = (Item) artikal.getSelectedItem();
        Item o = (Item) objekat.getSelectedItem();
        BigDecimal nova;
        try {
            nova = Ui.decimal(novaMpc, "Nova MPC", true);
        } catch (IllegalArgumentException ex) {
            Ui.info(this, ex.getMessage());
            return;
        }
        if (a == null || o == null) {
            return;
        }
        if (!Ui.confirm(this, "Nova MPC za \"" + a.naziv() + "\" na: " + o.naziv() + "\n\n" + Ui.km(nova) + " po jedinici. Nastaviti?")) {
            return;
        }
        Integer objId = Ui.idOrNull(objekat);
        Ui.async(this, () -> Repo.nivelacija(a.id(), nova, objId), res -> {
            String znak = res.razlika().signum() > 0 ? "+" : "";
            Ui.info(this, "Napravljeno dokumenata nivelacije: " + res.brojDokumenata()
                    + "\nPromjena vrijednosti zaliha (sa PDV-om): " + znak + Ui.km(res.razlika()));
            novaMpc.setText("");
            ucitaj();
        });
    }
}
