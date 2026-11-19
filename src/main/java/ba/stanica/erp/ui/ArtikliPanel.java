package ba.stanica.erp.ui;

import ba.stanica.erp.db.Repo;
import ba.stanica.erp.db.Repo.Item;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.util.List;

/** Šifarnik artikala: pretraga, unos i izmjena preko dbo.usp_Artikal_Sacuvaj. */
public class ArtikliPanel extends JPanel implements Ui.Refreshable {

    private final Ui.DataTable table = new Ui.DataTable("ArtikalId", "GrupaId");
    private final JTextField search = Ui.field(22, "Traži po šifri ili nazivu");
    private final JCheckBox neaktivni = new JCheckBox("Prikaži neaktivne");
    private final Timer debounce = new Timer(300, e -> refresh());

    public ArtikliPanel() {
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        neaktivni.addActionListener(e -> refresh());
        table.rowColor(r -> Boolean.FALSE.equals(table.value(r, "Aktivan")) ? new Color(0xF2F4F7) : null);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    izmijeni();
                }
            }
        });

        JPanel body = new JPanel(new BorderLayout());
        body.add(Ui.toolbar(search, neaktivni, Ui.primary("Novi artikal", e -> otvoriDialog(null)),
                Ui.button("Izmijeni", e -> izmijeni())), BorderLayout.NORTH);
        body.add(Ui.scroll(table), BorderLayout.CENTER);
        Ui.screen(this, Ui.title("Artikli", "Šifarnik robe. Snimanje ide kroz proceduru dbo.usp_Artikal_Sacuvaj "
                + "(validacija jedinstvene šifre je u bazi)."), body);
    }

    @Override
    public void refresh() {
        Ui.async(this, () -> Repo.artikliTabela(search.getText(), neaktivni.isSelected()), table::setData);
    }

    private void izmijeni() {
        int r = table.selectedModelRow();
        if (r < 0) {
            Ui.info(this, "Izaberite artikal u tabeli.");
            return;
        }
        otvoriDialog(r);
    }

    private void otvoriDialog(Integer row) {
        Ui.async(this, Repo::grupe, grupe -> {
            ArtikalDialog d = new ArtikalDialog(grupe, row);
            d.setVisible(true);
            if (d.sacuvano) {
                refresh();
            }
        });
    }

    private class ArtikalDialog extends JDialog {
        boolean sacuvano;
        private final Integer artikalId;
        private final JTextField sifra = Ui.field(12, "npr. G005");
        private final JTextField naziv = Ui.field(28, null);
        private final JComboBox<Item> grupa;
        private final JComboBox<String> jm = new JComboBox<>(new String[]{"L", "kom", "kg", "pak"});
        private final JTextField pdv = Ui.field(6, null);
        private final JTextField min = Ui.field(10, null);
        private final JCheckBox aktivan = new JCheckBox("Aktivan", true);
        private final JTextField mpc = Ui.field(10, "npr. 3,50");

        ArtikalDialog(List<Item> grupe, Integer row) {
            super(SwingUtilities.getWindowAncestor(ArtikliPanel.this),
                    row == null ? "Novi artikal" : "Izmjena artikla", ModalityType.APPLICATION_MODAL);
            grupa = Ui.combo(grupe, null);
            jm.setEditable(true);
            pdv.setText("17");
            min.setText("0");

            if (row != null) {
                artikalId = ((Number) table.value(row, "ArtikalId")).intValue();
                sifra.setText((String) table.value(row, "Sifra"));
                naziv.setText((String) table.value(row, "Naziv"));
                int g = ((Number) table.value(row, "GrupaId")).intValue();
                for (int i = 0; i < grupa.getItemCount(); i++) {
                    if (grupa.getItemAt(i).id() == g) {
                        grupa.setSelectedIndex(i);
                    }
                }
                jm.setSelectedItem(table.value(row, "JM"));
                pdv.setText(Ui.format("PdvStopa", table.value(row, "PdvStopa")));
                min.setText(Ui.format("MinZaliha", table.value(row, "MinZaliha")).replace(".", ""));
                aktivan.setSelected(Boolean.TRUE.equals(table.value(row, "Aktivan")));
            } else {
                artikalId = null;
            }

            JPanel form = row == null
                    ? Ui.form("Šifra", sifra, "Naziv", naziv, "Grupa", grupa, "Jedinica mjere", jm,
                    "PDV %", pdv, "Minimalna zaliha", min, "", aktivan, "Početna MPC (sve pumpe)", mpc)
                    : Ui.form("Šifra", sifra, "Naziv", naziv, "Grupa", grupa, "Jedinica mjere", jm,
                    "PDV %", pdv, "Minimalna zaliha", min, "", aktivan);
            form.setBorder(new EmptyBorder(16, 16, 8, 16));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(Ui.button("Odustani", e -> dispose()));
            JButton save = Ui.primary("Sačuvaj", e -> sacuvaj());
            buttons.add(save);
            getRootPane().setDefaultButton(save);

            add(form, BorderLayout.CENTER);
            add(buttons, BorderLayout.SOUTH);
            pack();
            setLocationRelativeTo(ArtikliPanel.this);
        }

        private void sacuvaj() {
            BigDecimal pdvV, minV, mpcV;
            try {
                pdvV = Ui.decimal(pdv, "PDV %", true);
                minV = Ui.decimal(min, "Minimalna zaliha", true);
                mpcV = artikalId == null ? Ui.decimal(mpc, "Početna MPC", false) : null;
            } catch (IllegalArgumentException ex) {
                Ui.info(this, ex.getMessage());
                return;
            }
            Item g = (Item) grupa.getSelectedItem();
            String j = String.valueOf(jm.getSelectedItem()).trim();
            Ui.async(this, () -> Repo.sacuvajArtikal(artikalId, sifra.getText(), naziv.getText(), g.id(), j,
                    pdvV, minV, aktivan.isSelected(), mpcV), id -> {
                sacuvano = true;
                dispose();
            });
        }
    }
}
