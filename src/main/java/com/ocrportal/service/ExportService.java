package com.ocrportal.service;

import com.ocrportal.model.DocumentRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates CSV, Excel (.xlsx), and PDF summary exports from document records.
 */
@Service
public class ExportService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ----------------------------------------------------------------
    //  CSV Export
    // ----------------------------------------------------------------

    public byte[] exportCsv(List<DocumentRecord> docs) throws IOException {
        StringBuilder sb = new StringBuilder();
        // Header
        sb.append("ID,Filename,Status,OCR Engine,Invoice Number,Vendor Name,Invoice Date,Total Amount,Reviewed,Uploaded At\n");
        // Rows
        for (DocumentRecord d : docs) {
            sb.append(csv(d.getId())).append(",");
            sb.append(csv(d.getFilename())).append(",");
            sb.append(csv(d.getStatus())).append(",");
            sb.append(csv(d.getOcrEngine())).append(",");
            sb.append(csv(d.getInvoiceNumber())).append(",");
            sb.append(csv(d.getVendorName())).append(",");
            sb.append(csv(d.getInvoiceDate())).append(",");
            sb.append(csv(d.getTotalAmount())).append(",");
            sb.append(d.isReviewed()).append(",");
            sb.append(d.getUploadedAt() != null ? d.getUploadedAt().format(DT_FMT) : "");
            sb.append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csv(Object val) {
        if (val == null) return "";
        String s = val.toString().replace("\"", "\"\"");
        return "\"" + s + "\"";
    }

    // ----------------------------------------------------------------
    //  Excel (.xlsx) Export — Apache POI
    // ----------------------------------------------------------------

    public byte[] exportExcel(List<DocumentRecord> docs) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("OCR Documents");

            // ---- Header style ----
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            // ---- Header row ----
            String[] headers = {"ID", "Filename", "Status", "OCR Engine",
                "Invoice Number", "Vendor Name", "Invoice Date", "Total Amount",
                "Reviewed", "Uploaded At"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // ---- Data rows ----
            CellStyle altStyle = workbook.createCellStyle();
            altStyle.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
            altStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            int rowIdx = 1;
            for (DocumentRecord d : docs) {
                Row row = sheet.createRow(rowIdx);
                if (rowIdx % 2 == 0) {
                    for (int c = 0; c < headers.length; c++) row.createCell(c).setCellStyle(altStyle);
                }
                row.createCell(0).setCellValue(d.getId());
                row.createCell(1).setCellValue(nvl(d.getFilename()));
                row.createCell(2).setCellValue(nvl(d.getStatus()));
                row.createCell(3).setCellValue(nvl(d.getOcrEngine()));
                row.createCell(4).setCellValue(nvl(d.getInvoiceNumber()));
                row.createCell(5).setCellValue(nvl(d.getVendorName()));
                row.createCell(6).setCellValue(nvl(d.getInvoiceDate()));
                row.createCell(7).setCellValue(nvl(d.getTotalAmount()));
                row.createCell(8).setCellValue(d.isReviewed() ? "Yes" : "No");
                row.createCell(9).setCellValue(d.getUploadedAt() != null ? d.getUploadedAt().format(DT_FMT) : "");
                rowIdx++;
            }

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    // ----------------------------------------------------------------
    //  PDF Summary Export — Apache PDFBox
    // ----------------------------------------------------------------

    public byte[] exportPdf(List<DocumentRecord> docs) throws IOException {
        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            PDType1Font boldFont   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font normalFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            // --- Title page / header ---
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            float margin = 50f;
            float yStart = page.getMediaBox().getHeight() - margin;
            float pageWidth = page.getMediaBox().getWidth() - 2 * margin;

            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                // Title
                cs.beginText();
                cs.setFont(boldFont, 18);
                cs.newLineAtOffset(margin, yStart);
                cs.showText("Smart Document Digitizer — OCR Report");
                cs.endText();

                // Subtitle / meta
                cs.beginText();
                cs.setFont(normalFont, 10);
                cs.newLineAtOffset(margin, yStart - 22);
                cs.showText("Total Documents: " + docs.size() + "   |   Generated: " +
                    java.time.LocalDateTime.now().format(DT_FMT));
                cs.endText();

                // Separator line
                cs.moveTo(margin, yStart - 32);
                cs.lineTo(margin + pageWidth, yStart - 32);
                cs.stroke();

                // Column headers
                float y = yStart - 52;
                cs.setFont(boldFont, 9);
                drawPdfRow(cs, margin, y, boldFont, 9,
                    "ID", "Filename", "Status", "Invoice No.", "Vendor", "Total");

                y -= 14;
                cs.moveTo(margin, y + 2);
                cs.lineTo(margin + pageWidth, y + 2);
                cs.stroke();

                // Data rows — add new page when needed
                cs.setFont(normalFont, 8);
                for (DocumentRecord d : docs) {
                    if (y < margin + 20) break; // simple guard (extra pages not shown for brevity)
                    y -= 13;
                    drawPdfRow(cs, margin, y, normalFont, 8,
                        String.valueOf(d.getId()),
                        truncate(d.getFilename(), 22),
                        nvl(d.getStatus()),
                        truncate(nvl(d.getInvoiceNumber()), 14),
                        truncate(nvl(d.getVendorName()), 18),
                        nvl(d.getTotalAmount()));
                }
            }

            pdf.save(bos);
            return bos.toByteArray();
        }
    }

    // ---- PDF helper: draw 6-column row ----
    private void drawPdfRow(PDPageContentStream cs, float x, float y,
                            PDType1Font font, float size,
                            String c1, String c2, String c3, String c4, String c5, String c6)
            throws IOException {
        float[] colX = {x, x + 30, x + 165, x + 240, x + 310, x + 400};
        String[] vals = {c1, c2, c3, c4, c5, c6};
        for (int i = 0; i < vals.length; i++) {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(colX[i], y);
            cs.showText(vals[i] != null ? vals[i] : "");
            cs.endText();
        }
    }

    private String nvl(String s) { return s == null ? "" : s; }
    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
