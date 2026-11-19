package ba.stanica.erp.ui;

import ba.stanica.erp.db.Db;
import ba.stanica.erp.db.Repo;
import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Dashboard: sve dolazi iz jedne procedure, dbo.usp_Pregled, koja vraća tri result seta. */
public class PregledPanel extends JPanel implements Ui.Refreshable {

    private final JLabel prometDanas = new JLabel("…");
    private final JLabel prometMjesec = new JLabel("…");
    private final JLabel rucMjesec = new JLabel("…");
    private final JLabel zalihe = new JLabel("…");
    private final JLabel ispodMin = new JLabel("…");
    private final JLabel otvoreni = new JLabel("…");
    private final Chart chart = new Chart();
    private final Ui.DataTable naruciti = new Ui.DataTable();

    public PregledPanel() {
        JPanel kpis = new JPanel(new GridLayout(1, 6, 12, 0));
        kpis.add(kpi("Promet danas", prometDanas, "sa PDV-om, sve pumpe i VP"));
        kpis.add(kpi("Promet ovaj mjesec", prometMjesec, "sa PDV-om"));
        kpis.add(kpi("RUC ovaj mjesec", rucMjesec, "razlika u cijeni, bez PDV-a"));
        kpis.add(kpi("Vrijednost zaliha", zalihe, "po prosječnoj nabavnoj cijeni"));
        kpis.add(kpi("Ispod minimuma", ispodMin, "artikala treba naručiti"));
        kpis.add(kpi("Otvoreni dokumenti", otvoreni, "čekaju knjiženje"));

        naruciti.rowColor(r -> Ui.DANGER_BG);

        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.add(kpis, BorderLayout.NORTH);
        JPanel lower = new JPanel(new GridLayout(1, 2, 14, 0));
        lower.add(Ui.card("Promet zadnjih 30 dana (KM sa PDV-om)", chart));
        lower.add(Ui.card("Treba naručiti: zaliha ispod minimuma", Ui.scroll(naruciti)));
        body.add(lower, BorderLayout.CENTER);

        Ui.screen(this, Ui.title("Pregled poslovanja",
                "KPI, grafikon i lista za naručivanje dolaze iz jedne procedure: dbo.usp_Pregled"), body);
    }

    private static JPanel kpi(String naslov, JLabel value, String opis) {
        value.putClientProperty(FlatClientProperties.STYLE, "font: bold +5");
        value.setForeground(new Color(0x101828));
        JLabel t = Ui.muted(naslov);
        t.putClientProperty(FlatClientProperties.STYLE, "font: bold");
        JLabel d = Ui.muted(opis);
        d.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        JPanel p = new JPanel(new GridLayout(3, 1, 0, 2));
        p.setBackground(Color.WHITE);
        p.setBorder(new CompoundBorder(new LineBorder(Ui.BORDER, 1, true), new EmptyBorder(12, 14, 12, 14)));
        p.add(t);
        p.add(value);
        p.add(d);
        return p;
    }

    @Override
    public void refresh() {
        Ui.async(this, Repo::pregled, rs -> {
            Db.Result k = rs.get(0);
            prometDanas.setText(Ui.km(k.get(0, "PrometDanas")));
            prometMjesec.setText(Ui.km(k.get(0, "PrometMjesec")));
            rucMjesec.setText(Ui.km(k.get(0, "RucMjesec")));
            zalihe.setText(Ui.km(k.get(0, "VrijednostZaliha")));
            int ispod = ((Number) k.get(0, "IspodMinimuma")).intValue();
            ispodMin.setText(String.valueOf(ispod));
            ispodMin.setForeground(ispod > 0 ? Ui.DANGER : Ui.ACCENT);
            otvoreni.setText(String.valueOf(k.get(0, "OtvorenihDokumenata")));
            chart.setData(rs.get(1));
            naruciti.setData(rs.get(2));
        });
    }

    /** Stubičasti grafikon nacrtan direktno u Java2D, bez dodatnih biblioteka. */
    static class Chart extends JComponent {
        private final List<LocalDate> dani = new ArrayList<>();
        private final List<Double> mp = new ArrayList<>();
        private final List<Double> vp = new ArrayList<>();
        private int hover = -1;
        private static final int LEFT = 58, RIGHT = 10, TOP = 30, BOTTOM = 28;

        Chart() {
            setPreferredSize(new Dimension(500, 300));
            setToolTipText("");
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int i = index(e.getX());
                    if (i != hover) {
                        hover = i;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        void setData(Db.Result r) {
            dani.clear();
            mp.clear();
            vp.clear();
            for (Object[] row : r.rows()) {
                dani.add(((java.sql.Date) row[0]).toLocalDate());
                mp.add(((BigDecimal) row[1]).doubleValue());
                vp.add(((BigDecimal) row[2]).doubleValue());
            }
            repaint();
        }

        private int index(int x) {
            if (dani.isEmpty()) {
                return -1;
            }
            double slot = (getWidth() - LEFT - RIGHT) / (double) dani.size();
            int i = (int) ((x - LEFT) / slot);
            return i >= 0 && i < dani.size() ? i : -1;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = index(e.getX());
            if (i < 0) {
                return null;
            }
            return "<html><b>" + dani.get(i).format(Ui.DATUM) + "</b><br>Maloprodaja: " + Ui.km(mp.get(i))
                    + "<br>Veleprodaja: " + Ui.km(vp.get(i)) + "<br>Ukupno: " + Ui.km(mp.get(i) + vp.get(i)) + "</html>";
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont().deriveFont(11f));
            int w = getWidth(), h = getHeight();
            int pw = w - LEFT - RIGHT, ph = h - TOP - BOTTOM;
            FontMetrics fm = g.getFontMetrics();

            // legenda
            legend(g, LEFT, 12, Ui.ACCENT, "Maloprodaja (pumpe)");
            legend(g, LEFT + 150, 12, Ui.ACCENT_2, "Veleprodaja (fakture)");

            if (dani.isEmpty() || pw <= 0 || ph <= 0) {
                g.dispose();
                return;
            }
            double max = 0;
            for (int i = 0; i < dani.size(); i++) {
                max = Math.max(max, mp.get(i) + vp.get(i));
            }
            double step = niceStep(max / 4);
            double top = Math.max(step, Math.ceil(max / step) * step);

            // mreža i y ose
            for (double v = 0; v <= top + 0.001; v += step) {
                int y = TOP + ph - (int) (v / top * ph);
                g.setColor(new Color(0xEEF0F2));
                g.drawLine(LEFT, y, w - RIGHT, y);
                g.setColor(Ui.MUTED);
                String s = v >= 1000 ? Ui.decimalFormat("#,##0").format(v / 1000) + "k" : Ui.decimalFormat("#,##0").format(v);
                g.drawString(s, LEFT - 8 - fm.stringWidth(s), y + 4);
            }

            double slot = pw / (double) dani.size();
            int bw = Math.max(2, (int) (slot * 0.68));
            for (int i = 0; i < dani.size(); i++) {
                int x = LEFT + (int) (i * slot + (slot - bw) / 2);
                int hMp = (int) Math.round(mp.get(i) / top * ph);
                int hVp = (int) Math.round(vp.get(i) / top * ph);
                int base = TOP + ph;
                if (i == hover) {
                    g.setColor(new Color(0xF2F4F7));
                    g.fillRect(LEFT + (int) (i * slot), TOP, (int) Math.ceil(slot), ph);
                }
                g.setColor(Ui.ACCENT);
                g.fillRect(x, base - hMp, bw, hMp);
                g.setColor(Ui.ACCENT_2);
                g.fillRect(x, base - hMp - hVp, bw, hVp);
                if (i % 5 == 4 || i == dani.size() - 1) {
                    String d = dani.get(i).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."));
                    g.setColor(Ui.MUTED);
                    g.drawString(d, x + bw / 2 - fm.stringWidth(d) / 2, base + 18);
                }
            }
            g.setColor(new Color(0xD0D5DD));
            g.drawLine(LEFT, TOP + ph, w - RIGHT, TOP + ph);
            g.dispose();
        }

        private static void legend(Graphics2D g, int x, int y, Color c, String text) {
            g.setColor(c);
            g.fillRoundRect(x, y - 9, 10, 10, 3, 3);
            g.setColor(new Color(0x344054));
            g.drawString(text, x + 15, y);
        }

        private static double niceStep(double raw) {
            if (raw <= 0) {
                return 1;
            }
            double mag = Math.pow(10, Math.floor(Math.log10(raw)));
            double n = raw / mag;
            return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
        }
    }
}
