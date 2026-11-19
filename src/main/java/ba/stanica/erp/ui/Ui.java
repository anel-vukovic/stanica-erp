package ba.stanica.erp.ui;

import ba.stanica.erp.db.Db;
import ba.stanica.erp.db.Repo.Item;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.File;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/** Zajedničke UI pomoćne klase: formatiranje, tabele, forme, asinhroni pozivi baze. */
public final class Ui {

    public static final Locale BS = Locale.forLanguageTag("bs-BA");
    public static final Color ACCENT = new Color(0x0F6E56);
    public static final Color ACCENT_2 = new Color(0xE0912F);
    public static final Color DANGER = new Color(0xB42318);
    public static final Color DANGER_BG = new Color(0xFDECEC);
    public static final Color WARN_BG = new Color(0xFFF4DB);
    public static final Color MUTED = new Color(0x667085);
    public static final Color BORDER = new Color(0xE4E7EC);
    public static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    public static final DateTimeFormatter DATUM_VRIJEME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private Ui() {
    }

    /** Panel koji učitava svježe podatke svaki put kad ga korisnik otvori (i na F5). */
    public interface Refreshable {
        void refresh();
    }

    // ================================================================ formatiranje

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("Sifra", "Šifra"), Map.entry("JedMjere", "JM"), Map.entry("PdvStopa", "PDV %"),
            Map.entry("MinZaliha", "Min. zaliha"), Map.entry("Kolicina", "Količina"),
            Map.entry("ProsjNabCijena", "Prosj. nab. cijena"), Map.entry("NabavnaVrijednost", "Nabavna vrijednost"),
            Map.entry("MaloprodajnaVrijednost", "MP vrijednost"), Map.entry("IspodMinimuma", "Ispod min."),
            Map.entry("IznosBezPdv", "Bez PDV"), Map.entry("IznosPdv", "PDV"), Map.entry("Pdv", "PDV"),
            Map.entry("IznosSaPdv", "Sa PDV"), Map.entry("ProdajaBezPdv", "Prodaja bez PDV"),
            Map.entry("ProdajaSaPdv", "Prodaja sa PDV"), Map.entry("RucPosto", "RUC %"),
            Map.entry("RabatPosto", "Rabat %"), Map.entry("NabCijena", "Nab. cijena"),
            Map.entry("StaraMPC", "Stara MPC"), Map.entry("NovaMPC", "Nova MPC"),
            Map.entry("VaziOd", "Važi od"), Map.entry("Stavki", "Stavki"), Map.entry("Nedostaje", "Nedostaje"));

    public static String label(String column) {
        return LABELS.getOrDefault(column, column);
    }

    private static final Set<String> QTY = Set.of("Kolicina", "Stanje", "Ulaz", "Izlaz", "MinZaliha", "Nedostaje");
    private static final Set<String> PRICE = Set.of("Cijena", "MPC", "NabCijena", "ProsjNabCijena", "StaraMPC",
            "NovaMPC", "Promjena");

    public static DecimalFormat decimalFormat(String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(BS));
    }

    /** Formatira vrijednost iz baze prema nazivu kolone (količina, cijena, iznos, datum). */
    public static String format(String column, Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) {
            String pattern = QTY.contains(column) ? "#,##0.00#" : PRICE.contains(column) ? "#,##0.00##" : "#,##0.00";
            return decimalFormat(pattern).format(v);
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            return decimalFormat("#,##0").format(v);
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().format(DATUM);
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().format(DATUM_VRIJEME);
        }
        if (v instanceof Boolean b) {
            return b ? "Da" : "Ne";
        }
        return v.toString();
    }

    public static String km(Object v) {
        return v == null ? "0,00 KM" : decimalFormat("#,##0.00").format(v) + " KM";
    }

    // ================================================================ asinhrono

    /**
     * Upit ide u pozadinskoj niti (SwingWorker), a rezultat se vraća na EDT.
     * Tako prozor nikad ne "zamrzne" dok baza radi.
     */
    public static <T> void async(Component owner, Callable<T> work, Consumer<T> done) {
        Component root = owner == null ? null : SwingUtilities.getRoot(owner);
        if (root != null) {
            root.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                if (root != null) {
                    root.setCursor(Cursor.getDefaultCursor());
                }
                try {
                    done.accept(get());
                } catch (ExecutionException e) {
                    error(owner, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    public static void error(Component owner, Throwable t) {
        String msg = t instanceof SQLException s ? Db.friendly(s) : String.valueOf(t.getMessage());
        JOptionPane.showMessageDialog(owner, html(msg), "Greška", JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component owner, String msg) {
        JOptionPane.showMessageDialog(owner, html(msg), "StanicaERP", JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component owner, String msg) {
        Object[] options = {"Da", "Ne"};
        return JOptionPane.showOptionDialog(owner, html(msg), "Potvrda", JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]) == 0;
    }

    private static String html(String msg) {
        String safe = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return "<html><body style='width: 380px'>" + safe + "</body></html>";
    }

    // ================================================================ tabela

    /** JTable koji prikazuje Db.Result, sa formatiranjem, skrivenim kolonama i bojenjem redova. */
    public static class DataTable extends JTable {
        private final ResultModel model = new ResultModel();
        private final Set<String> hidden = new HashSet<>();
        private IntFunction<Color> rowColor = r -> null;
        private IntPredicate boldRow = r -> false;

        public DataTable(String... hiddenColumns) {
            setModel(model);
            hidden.addAll(List.of(hiddenColumns));
            setAutoCreateRowSorter(true);
            setRowHeight(28);
            setFillsViewportHeight(true);
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
            getTableHeader().setReorderingAllowed(false);
            CellRenderer r = new CellRenderer();
            for (Class<?> c : List.of(Object.class, Number.class, BigDecimal.class, Integer.class, Long.class,
                    Double.class, java.sql.Date.class, Timestamp.class, Boolean.class, String.class)) {
                setDefaultRenderer(c, r);
            }
        }

        public void setData(Db.Result r) {
            model.set(r);
            List<TableColumn> remove = new ArrayList<>();
            Enumeration<TableColumn> cols = getColumnModel().getColumns();
            while (cols.hasMoreElements()) {
                TableColumn c = cols.nextElement();
                if (hidden.contains(model.column(c.getModelIndex()))) {
                    remove.add(c);
                }
            }
            remove.forEach(this::removeColumn);
            packColumns();
        }

        public Db.Result result() {
            return model.r;
        }

        public void rowColor(IntFunction<Color> f) {
            rowColor = f;
        }

        public void boldRow(IntPredicate p) {
            boldRow = p;
        }

        /** Izabrani red u modelu (nezavisno od sortiranja) ili -1. */
        public int selectedModelRow() {
            int v = getSelectedRow();
            return v < 0 ? -1 : convertRowIndexToModel(v);
        }

        public Object value(int modelRow, String column) {
            return model.r.get(modelRow, column);
        }

        private void packColumns() {
            FontMetrics fm = getFontMetrics(getFont());
            FontMetrics hfm = getFontMetrics(getTableHeader().getFont());
            for (int c = 0; c < getColumnCount(); c++) {
                TableColumn col = getColumnModel().getColumn(c);
                String name = model.column(col.getModelIndex());
                int w = hfm.stringWidth(label(name)) + 28;
                int rows = Math.min(model.r.rows().size(), 300);
                for (int r = 0; r < rows; r++) {
                    w = Math.max(w, fm.stringWidth(format(name, model.r.rows().get(r)[col.getModelIndex()])) + 24);
                }
                col.setPreferredWidth(Math.min(w, 340));
                // kratke kolone (šifre, brojevi, datumi) se ne smiju stisnuti; dugi tekst može
                col.setMinWidth(w <= 120 ? w : 70);
            }
        }

        private class CellRenderer extends DefaultTableCellRenderer {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                           int row, int col) {
                int modelCol = t.convertColumnIndexToModel(col);
                int modelRow = t.convertRowIndexToModel(row);
                String name = model.column(modelCol);
                Component c = super.getTableCellRendererComponent(t, format(name, v), sel, focus, row, col);
                setHorizontalAlignment(v instanceof Number ? RIGHT : LEFT);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                if (!sel) {
                    Color bg = rowColor.apply(modelRow);
                    c.setBackground(bg != null ? bg
                            : (row % 2 == 1 ? UIManager.getColor("Table.alternateRowColor") : t.getBackground()));
                    c.setForeground(t.getForeground());
                }
                c.setFont(boldRow.test(modelRow) ? t.getFont().deriveFont(Font.BOLD) : t.getFont());
                return c;
            }
        }
    }

    static class ResultModel extends AbstractTableModel {
        private Db.Result r = Db.Result.EMPTY;

        void set(Db.Result result) {
            r = result;
            fireTableStructureChanged();
        }

        String column(int c) {
            return r.columns().get(c);
        }

        @Override
        public int getRowCount() {
            return r.rows().size();
        }

        @Override
        public int getColumnCount() {
            return r.columns().size();
        }

        @Override
        public String getColumnName(int c) {
            return label(r.columns().get(c));
        }

        @Override
        public Class<?> getColumnClass(int c) {
            Class<?> t = r.types().get(c);
            return Number.class.isAssignableFrom(t) ? t : Object.class;   // brojevi se sortiraju numerički
        }

        @Override
        public Object getValueAt(int row, int c) {
            return r.rows().get(row)[c];
        }
    }

    // ================================================================ komponente

    public static JLabel title(String text, String subtitle) {
        JLabel l = new JLabel("<html><span style='font-size:16pt; font-weight:bold'>" + text + "</span>"
                + (subtitle == null ? "" : "<br><span style='color:#667085'>" + subtitle + "</span>") + "</html>");
        l.setBorder(new EmptyBorder(0, 0, 10, 0));
        return l;
    }

    public static JButton button(String text, ActionListener a) {
        JButton b = new JButton(text);
        b.addActionListener(a);
        return b;
    }

    public static JButton primary(String text, ActionListener a) {
        JButton b = button(text, a);
        b.putClientProperty(FlatClientProperties.STYLE,
                "background: #0F6E56; foreground: #FFFFFF; hoverBackground: #0C5A46; pressedBackground: #09473A;"
                        + " font: bold; borderWidth: 0; focusWidth: 0");
        return b;
    }

    public static JPanel toolbar(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        p.setBorder(new EmptyBorder(0, -8, 4, 0));
        for (Component c : cs) {
            p.add(c);
        }
        return p;
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        return l;
    }

    /** Bijela kartica sa naslovom (sekcija ekrana). */
    public static JPanel card(String title, JComponent body) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Color.WHITE);
        p.setBorder(new CompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(12, 14, 12, 14)));
        if (title != null) {
            JLabel t = new JLabel(title);
            t.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
            p.add(t, BorderLayout.NORTH);
        }
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    public static JScrollPane scroll(Component c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(new LineBorder(BORDER));
        return s;
    }

    /** Forma u dvije kolone: labela, polje, labela, polje... */
    public static JPanel form(Object... labelAndField) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 4, 5, 4);
        g.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labelAndField.length; i += 2) {
            g.gridy = i / 2;
            g.gridx = 0;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            p.add(new JLabel(String.valueOf(labelAndField[i])), g);
            g.gridx = 1;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            p.add((Component) labelAndField[i + 1], g);
        }
        return p;
    }

    public static JTextField field(int cols, String placeholder) {
        JTextField f = new JTextField(cols);
        if (placeholder != null) {
            f.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        }
        return f;
    }

    public static JSpinner dateSpinner(LocalDate d) {
        JSpinner s = new JSpinner(new SpinnerDateModel());
        s.setEditor(new JSpinner.DateEditor(s, "dd.MM.yyyy"));
        s.setValue(Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        s.setPreferredSize(new Dimension(118, s.getPreferredSize().height));
        return s;
    }

    public static LocalDate date(JSpinner s) {
        return ((Date) s.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** Parsira broj, prihvata i zarez i tačku kao decimalni separator. */
    public static BigDecimal decimal(JTextField f, String naziv, boolean obavezno) {
        String t = f.getText().trim().replace(" ", "").replace(',', '.');
        if (t.isEmpty()) {
            if (obavezno) {
                throw new IllegalArgumentException("Polje \"" + naziv + "\" je obavezno.");
            }
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Polje \"" + naziv + "\" mora biti broj (npr. 2,75).");
        }
    }

    public static JComboBox<Item> combo(List<Item> items, String allLabel) {
        JComboBox<Item> c = new JComboBox<>();
        fill(c, items, allLabel);
        return c;
    }

    public static void fill(JComboBox<Item> c, List<Item> items, String allLabel) {
        c.removeAllItems();
        if (allLabel != null) {
            c.addItem(new Item(-1, allLabel));
        }
        items.forEach(c::addItem);
    }

    public static Integer idOrNull(JComboBox<Item> c) {
        Item i = (Item) c.getSelectedItem();
        return i == null || i.id() < 0 ? null : i.id();
    }

    public static File savePdf(Component owner, String defaultName) {
        JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home"), "Desktop"));
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), defaultName));
        fc.setFileFilter(new FileNameExtensionFilter("PDF dokument", "pdf"));
        if (fc.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File f = fc.getSelectedFile();
        return f.getName().toLowerCase().endsWith(".pdf") ? f : new File(f.getParentFile(), f.getName() + ".pdf");
    }

    public static void open(Component owner, File f) {
        try {
            Desktop.getDesktop().open(f);
        } catch (Exception e) {
            info(owner, "PDF je sačuvan: " + f.getAbsolutePath());
        }
    }

    /** Okvir ekrana: naslov gore, sadržaj u sredini, uz standardne margine. */
    public static void screen(JPanel p, JComponent header, JComponent body) {
        p.setLayout(new BorderLayout(0, 6));
        p.setBorder(new EmptyBorder(18, 20, 14, 20));
        p.add(header, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
    }

    public static JPanel stack(JComponent... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (JComponent c : cs) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(c);
        }
        return p;
    }

    static TableCellRenderer noop() {
        return new DefaultTableCellRenderer();
    }
}
