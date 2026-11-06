import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal text-only PDF writer for account statements (no library needed). */
final class Pdf {
    private Pdf() {}

    private static final int W = 595, H = 842, MARGIN = 40;

    /** rows: {date, ref, description, debit, credit, balance} (already formatted). */
    static byte[] statement(String title, List<String> headerLines, List<String[]> rows, List<String> footerLines) {
        int[] colX = {MARGIN, 106, 194, 380, 442, 504};
        int[] colMax = {14, 14, 42, 12, 12, 13};
        List<StringBuilder> pages = new ArrayList<>();
        StringBuilder cur = newPage(pages);
        int y = H - MARGIN;
        text(cur, "F2", 16, MARGIN, y, title);
        y -= 20;
        for (String h : headerLines) { text(cur, "F1", 10, MARGIN, y, h); y -= 13; }
        y -= 8;
        y = tableHeader(cur, colX, y);
        for (String[] r : rows) {
            if (y < 60) {
                cur = newPage(pages);
                y = tableHeader(cur, colX, H - MARGIN);
            }
            for (int c = 0; c < r.length; c++) {
                String v = r[c];
                if (v.length() > colMax[c]) v = v.substring(0, colMax[c] - 1) + ".";
                text(cur, "F1", 8, colX[c], y, v);
            }
            y -= 13;
        }
        y -= 10;
        for (String f : footerLines) {
            if (y < 50) { cur = newPage(pages); y = H - MARGIN; }
            text(cur, "F1", 9, MARGIN, y, f);
            y -= 12;
        }
        return build(pages);
    }

    private static StringBuilder newPage(List<StringBuilder> pages) {
        StringBuilder sb = new StringBuilder();
        pages.add(sb);
        return sb;
    }

    private static int tableHeader(StringBuilder sb, int[] colX, int y) {
        String[] h = {"Date", "Ref", "Details", "Debit", "Credit", "Balance"};
        for (int c = 0; c < h.length; c++) text(sb, "F2", 8, colX[c], y, h[c]);
        sb.append("0.5 w ").append(MARGIN).append(' ').append(y - 4).append(" m ").append(W - MARGIN).append(' ').append(y - 4).append(" l S\n");
        return y - 16;
    }

    private static void text(StringBuilder sb, String font, int size, int x, int y, String s) {
        sb.append("BT /").append(font).append(' ').append(size).append(" Tf ").append(x).append(' ').append(y)
                .append(" Td (").append(esc(s)).append(") Tj ET\n");
    }

    /** PDF base fonts only cover Latin-1, so anything else (e.g. the rupee sign) is replaced. */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') b.append('\\').append(c);
            else if (c < 32 || c > 126) b.append('?');
            else b.append(c);
        }
        return b.toString();
    }

    private static byte[] build(List<StringBuilder> pages) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        w(out, "%PDF-1.4\n");
        int n = pages.size();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(5 + 2 * i).append(" 0 R ");
        obj(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        obj(out, offsets, 2, "<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>");
        obj(out, offsets, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        obj(out, offsets, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < n; i++) {
            int pageObj = 5 + 2 * i, contentObj = 6 + 2 * i;
            obj(out, offsets, pageObj, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + W + " " + H + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + contentObj + " 0 R >>");
            String content = pages.get(i).toString();
            obj(out, offsets, contentObj, "<< /Length " + content.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n" + content + "endstream");
        }
        int xref = out.size();
        w(out, "xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) w(out, String.format("%010d 00000 n \n", off));
        w(out, "trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void obj(ByteArrayOutputStream out, List<Integer> offsets, int num, String body) {
        offsets.add(out.size());
        w(out, num + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void w(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
    }
}
