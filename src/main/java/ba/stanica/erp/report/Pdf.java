package ba.stanica.erp.report;

import ba.stanica.erp.db.Db;
import ba.stanica.erp.ui.Ui;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Predicate;

/** PDF izvještaji i dokumenti (OpenPDF). Cp1250 kodiranje pokriva č, ć, đ, š, ž. */
public final class Pdf {

    private static final Color ACCENT = new Color(0x0F6E56);
    private static final Color HEAD_BG = new Color(0xE8F3EF);
    private static final Font F;
    private static final Font FB;
    private static final Font FH;
    private static final Font FTITLE;
    private static final Font FMUTED;

    static {
        try {
            BaseFont regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1250, BaseFont.NOT_EMBEDDED);
            BaseFont bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1250, BaseFont.NOT_EMBEDDED);
            F = new Font(regular, 8.5f);
            FB = new Font(bold, 8.5f);
            FH = new Font(bold, 8f, Font.NORMAL, ACCENT);
            FTITLE = new Font(bold, 16f, Font.NORMAL, new Color(0x101828));
            FMUTED = new Font(regular, 8.5f, Font.NORMAL, new Color(0x667085));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Pdf() {
    }

    /** Tabelarni izvještaj (A4 položeno). */
    public static void izvjestaj(File file, String naslov, String podnaslov, Db.Result r, Predicate<Object[]> bold)
            throws Exception {
        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        try (OutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(doc, out);
            doc.open();
            zaglavlje(doc, naslov, podnaslov);
            doc.add(tabela(r, bold, new String[0]));
            doc.add(new Paragraph("Generisano " + LocalDateTime.now().format(Ui.DATUM_VRIJEME)
                    + "  ·  StanicaERP  ·  podaci iz dbo.usp_Izvjestaj_Prodaja", FMUTED));
            doc.close();
        }
    }

    /** Štampani oblik dokumenta: kalkulacija, račun, faktura ili nivelacija. */
    public static void dokument(File file, Db.Result z, Db.Result stavke) throws Exception {
        String vrsta = (String) z.get(0, "Vrsta");
        String naslov = switch (vrsta) {
            case "KAL" -> "Kalkulacija (prijem robe)";
            case "RAC" -> "Maloprodajni račun (zbirni)";
            case "FAK" -> "Faktura";
            default -> "Zapisnik o nivelaciji cijena";
        };
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        try (OutputStream out = new FileOutputStream(file)) {
            PdfWriter.getInstance(doc, out);
            doc.open();
            zaglavlje(doc, naslov + "  " + z.get(0, "Broj"),
                    "Datum: " + Ui.format("Datum", z.get(0, "Datum"))
                            + ("P".equals(z.get(0, "Status")) ? "   ·   proknjižen" : "   ·   OTVOREN (nije proknjižen)"));

            PdfPTable info = new PdfPTable(2);
            info.setWidthPercentage(100);
            info.setSpacingAfter(12);
            info.addCell(blok("Objekat", z.get(0, "Objekat") + "\n" + nz(z.get(0, "ObjekatGrad"))));
            String partnerNaslov = "KAL".equals(vrsta) ? "Dobavljač" : "Kupac";
            info.addCell(z.get(0, "Partner") == null ? blok("", "")
                    : blok(partnerNaslov, z.get(0, "Partner") + "\nJIB: " + nz(z.get(0, "JIB")) + "\n"
                    + nz(z.get(0, "PartnerGrad"))));
            doc.add(info);

            doc.add(tabela(stavke, row -> false, new String[]{"StavkaId"}));

            BigDecimal bez = BigDecimal.ZERO, pdv = BigDecimal.ZERO, sa = BigDecimal.ZERO;
            for (int i = 0; i < stavke.rows().size(); i++) {
                bez = bez.add((BigDecimal) stavke.get(i, "IznosBezPdv"));
                pdv = pdv.add((BigDecimal) stavke.get(i, "IznosPdv"));
                sa = sa.add((BigDecimal) stavke.get(i, "IznosSaPdv"));
            }
            PdfPTable tot = new PdfPTable(2);
            tot.setWidthPercentage(40);
            tot.setHorizontalAlignment(Element.ALIGN_RIGHT);
            tot.setSpacingBefore(10);
            red(tot, "Osnovica (bez PDV-a)", Ui.km(bez), F);
            red(tot, "PDV 17 %", Ui.km(pdv), F);
            red(tot, "UKUPNO", Ui.km(sa), FB);
            doc.add(tot);

            if (z.get(0, "Napomena") != null) {
                doc.add(new Paragraph("Napomena: " + z.get(0, "Napomena"), FMUTED));
            }
            Paragraph potpis = new Paragraph("\n\n\nSastavio: ______________________"
                    + "                                        Primio: ______________________", F);
            doc.add(potpis);
            doc.close();
        }
    }

    private static void zaglavlje(Document doc, String naslov, String podnaslov) throws Exception {
        Paragraph brand = new Paragraph("StanicaERP", new Font(FH.getBaseFont(), 9f, Font.NORMAL, ACCENT));
        doc.add(brand);
        Paragraph t = new Paragraph(naslov, FTITLE);
        t.setSpacingBefore(2);
        doc.add(t);
        Paragraph s = new Paragraph(podnaslov, FMUTED);
        s.setSpacingAfter(10);
        doc.add(s);
    }

    private static PdfPTable tabela(Db.Result r, Predicate<Object[]> bold, String[] skip) {
        java.util.List<Integer> cols = new java.util.ArrayList<>();
        for (int i = 0; i < r.columns().size(); i++) {
            if (!java.util.List.of(skip).contains(r.columns().get(i))) {
                cols.add(i);
            }
        }
        PdfPTable t = new PdfPTable(cols.size());
        t.setWidthPercentage(100);
        // nazivi dobiju više mjesta, kratke kolone (šifra, JM) manje
        float[] widths = new float[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            String name = r.columns().get(cols.get(k));
            widths[k] = switch (name) {
                case "Artikal", "Naziv", "Objekat", "Partner", "Opis" -> 3.2f;
                case "Grupa" -> 2.2f;
                case "Sifra", "JM" -> 0.9f;
                default -> 1.3f;
            };
        }
        try {
            t.setWidths(widths);
        } catch (Exception ignored) {
            // broj širina uvijek odgovara broju kolona
        }
        t.setHeaderRows(1);
        for (int c : cols) {
            PdfPCell h = new PdfPCell(new Phrase(Ui.label(r.columns().get(c)), FH));
            h.setBackgroundColor(HEAD_BG);
            h.setBorderColor(new Color(0xD0D5DD));
            h.setPadding(5);
            boolean num = Number.class.isAssignableFrom(r.types().get(c));
            h.setHorizontalAlignment(num ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            t.addCell(h);
        }
        for (Object[] row : r.rows()) {
            boolean b = bold.test(row);
            for (int c : cols) {
                Object v = row[c];
                PdfPCell cell = new PdfPCell(new Phrase(Ui.format(r.columns().get(c), v), b ? FB : F));
                cell.setBorderColor(new Color(0xE4E7EC));
                cell.setPadding(4);
                if (b) {
                    cell.setBackgroundColor(HEAD_BG);
                }
                cell.setHorizontalAlignment(v instanceof Number ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                t.addCell(cell);
            }
        }
        return t;
    }

    private static PdfPCell blok(String naslov, String tekst) {
        Phrase p = new Phrase();
        p.add(new Chunk(naslov + "\n", FH));
        p.add(new Chunk(tekst, F));
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingBottom(6);
        return c;
    }

    private static void red(PdfPTable t, String l, String v, Font f) {
        PdfPCell a = new PdfPCell(new Phrase(l, f));
        PdfPCell b = new PdfPCell(new Phrase(v, f));
        for (PdfPCell c : new PdfPCell[]{a, b}) {
            c.setBorder(Rectangle.TOP);
            c.setBorderColor(new Color(0xE4E7EC));
            c.setPadding(4);
        }
        b.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(a);
        t.addCell(b);
    }

    private static String nz(Object o) {
        return o == null ? "" : o.toString();
    }
}
