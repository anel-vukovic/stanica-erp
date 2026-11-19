package ba.stanica.erp;

import ba.stanica.erp.db.Db;
import ba.stanica.erp.ui.ArtikliPanel;
import ba.stanica.erp.ui.DokumentiPanel;
import ba.stanica.erp.ui.IzvjestajiPanel;
import ba.stanica.erp.ui.LagerPanel;
import ba.stanica.erp.ui.NivelacijaPanel;
import ba.stanica.erp.ui.PregledPanel;
import ba.stanica.erp.ui.Ui;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Glavni prozor: navigacija lijevo, ekrani u CardLayout-u, status konekcije dole. */
public class App extends JFrame {

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<String, JComponent> panels = new LinkedHashMap<>();
    private final Map<String, JToggleButton> buttons = new LinkedHashMap<>();
    private final ButtonGroup navGroup = new ButtonGroup();
    private final JPanel nav = new JPanel();
    private final JLabel status = new JLabel("Povezujem se na SQL Server…");
    private String current;

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "StanicaERP");
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#0F6E56"));
        FlatLightLaf.setup();
        UIManager.put("Table.alternateRowColor", new Color(0xF8FAF9));
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        UIManager.put("Table.gridColor", new Color(0xEEF0F2));
        UIManager.put("TableHeader.height", 30);
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("Panel.background", new Color(0xF5F7F6));

        SwingUtilities.invokeLater(() -> {
            App app = new App();
            app.setVisible(true);
            if (args.length == 2 && args[0].equals("--snimci")) {
                app.snimci(new File(args[1]));
            }
        });
    }

    public App() {
        super("StanicaERP");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1400, 880);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(null);

        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBackground(new Color(0x0B3D33));
        nav.setBorder(new EmptyBorder(20, 12, 16, 12));
        nav.setPreferredSize(new Dimension(212, 0));

        JLabel logo = new JLabel("StanicaERP");
        logo.setForeground(Color.WHITE);
        logo.putClientProperty(FlatClientProperties.STYLE, "font: bold +9");
        JLabel sub = new JLabel("<html>Maloprodaja i veleprodaja goriva<br>Java Swing · MS SQL Server</html>");
        sub.setForeground(new Color(0x9CC9BC));
        sub.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        for (JComponent c : new JComponent[]{logo, sub}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            c.setBorder(new EmptyBorder(0, 10, 0, 0));
        }
        nav.add(logo);
        nav.add(Box.createVerticalStrut(4));
        nav.add(sub);
        nav.add(Box.createVerticalStrut(26));

        addPanel("Pregled", new PregledPanel());
        addPanel("Artikli", new ArtikliPanel());
        addPanel("Lager", new LagerPanel());
        addPanel("Dokumenti", new DokumentiPanel());
        addPanel("Nivelacija cijena", new NivelacijaPanel());
        addPanel("Izvještaji", new IzvjestajiPanel());

        nav.add(Box.createVerticalGlue());
        JLabel hint = new JLabel("<html>F5 osvježava ekran<br>⌘1 – ⌘6 prelazak između ekrana</html>");
        hint.setForeground(new Color(0x7FB3A5));
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        hint.setBorder(new EmptyBorder(0, 10, 0, 0));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        nav.add(hint);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(Color.WHITE);
        statusBar.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Ui.BORDER), new EmptyBorder(5, 14, 5, 14)));
        status.setForeground(Ui.MUTED);
        statusBar.add(status, BorderLayout.WEST);

        add(nav, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(e -> refreshCurrent(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        show("Pregled");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return Db.serverInfo();
            }

            @Override
            protected void done() {
                try {
                    String s = get();
                    status.setText("●  " + s);
                    status.setForeground(s.startsWith("Povezano") ? Ui.ACCENT : Ui.DANGER);
                } catch (Exception ignored) {
                    // poruka je već u statusu
                }
            }
        }.execute();
    }

    private void addPanel(String name, JComponent panel) {
        int index = panels.size();
        panels.put(name, panel);
        content.add(panel, name);

        JToggleButton b = new JToggleButton(name);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        b.putClientProperty(FlatClientProperties.STYLE,
                "foreground: #D8EEE7; selectedForeground: #FFFFFF; "
                        + "toolbar.selectedBackground: #17614F; toolbar.hoverBackground: #145246; "
                        + "toolbar.pressedBackground: #17614F; font: +1; margin: 9,12,9,12");
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.setFocusable(false);
        b.addActionListener(e -> show(name));
        navGroup.add(b);
        buttons.put(name, b);
        nav.add(b);
        nav.add(Box.createVerticalStrut(3));

        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane().registerKeyboardAction(e -> show(name),
                KeyStroke.getKeyStroke(KeyEvent.VK_1 + index, mask), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void show(String name) {
        current = name;
        cards.show(content, name);
        buttons.get(name).setSelected(true);
        refreshCurrent();
    }

    private void refreshCurrent() {
        if (panels.get(current) instanceof Ui.Refreshable r) {
            r.refresh();
        }
    }

    /** Pomoćni režim: otvori svaki ekran i sačuva sliku (za README i provjeru izgleda). */
    private void snimci(File dir) {
        dir.mkdirs();
        List<String> names = new ArrayList<>(panels.keySet());
        Timer t = new Timer(3500, null);
        int[] i = {0};
        show(names.get(0));
        t.addActionListener(e -> {
            try {
                BufferedImage img = new BufferedImage(getContentPane().getWidth(), getContentPane().getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                getContentPane().paint(g);
                g.dispose();
                String file = String.format("%d-%s.png", i[0] + 1,
                        names.get(i[0]).toLowerCase().replace(' ', '-').replace("š", "s").replace("ć", "c"));
                ImageIO.write(img, "png", new File(dir, file));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            i[0]++;
            if (i[0] >= names.size()) {
                t.stop();
                snimiDijaloge(dir);
                return;
            }
            show(names.get(i[0]));
        });
        t.start();
    }

    private void snimiDijaloge(File dir) {
        DokumentiPanel dok = (DokumentiPanel) panels.get("Dokumenti");
        LagerPanel lager = (LagerPanel) panels.get("Lager");
        Integer otvoren = null;
        try {
            Db.Result r = Db.query("SELECT TOP (1) DokumentId FROM dbo.Dokument WHERE Status = 'O' ORDER BY DokumentId DESC");
            otvoren = r.isEmpty() ? null : ((Number) r.rows().get(0)[0]).intValue();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        List<Object[]> koraci = new ArrayList<>();
        koraci.add(new Object[]{"7-novi-dokument.png", dok.dialogZaSnimak(null)});
        if (otvoren != null) {
            koraci.add(new Object[]{"8-otvoren-dokument.png", dok.dialogZaSnimak(otvoren)});
        }
        koraci.add(new Object[]{"9-kartica-artikla.png",
                lager.karticaZaSnimak(1, 1, "G001 Eurodizel BS  ·  Pumpa Sarajevo - Stup")});
        snimiKorak(dir, koraci, 0);
    }

    private void snimiKorak(File dir, List<Object[]> koraci, int k) {
        if (k >= koraci.size()) {
            System.exit(0);
        }
        JDialog d = (JDialog) koraci.get(k)[1];
        d.setVisible(true);
        Timer t = new Timer(3000, null);
        t.setRepeats(false);
        t.addActionListener(e -> {
            try {
                Container c = d.getContentPane();
                BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                c.paint(g);
                g.dispose();
                ImageIO.write(img, "png", new File(dir, (String) koraci.get(k)[0]));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            d.dispose();
            snimiKorak(dir, koraci, k + 1);
        });
        t.start();
    }
}
