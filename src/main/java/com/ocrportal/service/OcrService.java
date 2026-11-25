package com.ocrportal.service;

import com.ocrportal.model.DocumentRecord;
import com.ocrportal.repository.DocumentRepository;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core service: handles file storage, OCR (PDFBox + Tess4J),
 * and invoice field extraction via regex.
 */
@Service
public class OcrService {

    private static final Logger log = Logger.getLogger(OcrService.class.getName());

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Value("${tesseract.data-path}")
    private String tessDataPath;

    @Value("${tesseract.language}")
    private String tessLanguage;

    private final DocumentRepository repository;

    public OcrService(DocumentRepository repository) {
        this.repository = repository;
    }

    // ----------------------------------------------------------------
    //  PUBLIC: process an uploaded file end-to-end
    // ----------------------------------------------------------------

    /**
     * Save the file, run OCR, extract fields, persist to DB.
     * @return the saved DocumentRecord
     */
    public DocumentRecord processUpload(MultipartFile file) throws IOException {

        // 1. Persist the uploaded file to disk
        File savedFile = saveFileToDisk(file);

        // 2. Create initial DB record
        DocumentRecord doc = new DocumentRecord();
        doc.setFilename(file.getOriginalFilename());
        doc.setStoredPath(savedFile.getAbsolutePath());
        doc.setMimeType(file.getContentType());
        doc.setStatus("PROCESSING");
        doc.setUploadedAt(LocalDateTime.now());
        doc.setReviewed(false);
        doc = repository.save(doc);

        // 3. Extract text via the right engine
        try {
            String extractedText = extractText(savedFile, file.getContentType());
            doc.setRawText(extractedText);
            doc.setStatus("COMPLETED");
            doc.setOcrEngine(determineEngine(savedFile, file.getContentType(), extractedText));
        } catch (Exception e) {
            log.log(Level.WARNING, "OCR failed for " + file.getOriginalFilename(), e);
            doc.setStatus("FAILED");
            doc.setRawText("OCR Error: " + e.getMessage());
            doc.setOcrEngine("None");
        }

        // 4. Extract structured invoice fields from raw text
        if (doc.getRawText() != null) {
            doc.setInvoiceNumber(extractInvoiceNumber(doc.getRawText()));
            doc.setVendorName(extractVendorName(doc.getRawText()));
            doc.setInvoiceDate(extractInvoiceDate(doc.getRawText()));
            doc.setTotalAmount(extractTotalAmount(doc.getRawText()));
        }

        return repository.save(doc);
    }

    // ----------------------------------------------------------------
    //  File storage
    // ----------------------------------------------------------------

    private File saveFileToDisk(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(uploadDir);
        Files.createDirectories(uploadPath);

        String uniqueName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path targetPath = uploadPath.resolve(uniqueName);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        return targetPath.toFile();
    }

    // ----------------------------------------------------------------
    //  OCR text extraction
    // ----------------------------------------------------------------

    /**
     * Routes to PDFBox or Tess4J depending on file type.
     * Falls back to Tess4J if PDFBox yields fewer than 30 chars.
     */
    private String extractText(File file, String mimeType) throws IOException, TesseractException {
        if ("application/pdf".equalsIgnoreCase(mimeType)) {
            return extractTextFromPdf(file);
        } else {
            // PNG / JPEG — go straight to Tess4J
            return runTesseract(file);
        }
    }

    /**
     * Try PDFBox text layer first; if sparse, render pages to images and use Tess4J.
     */
    private String extractTextFromPdf(File pdfFile) throws IOException, TesseractException {
        String pdfBoxText = "";
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            pdfBoxText = stripper.getText(document).trim();
        }

        if (pdfBoxText.length() >= 30) {
            return pdfBoxText;   // Digital PDF — text layer is good
        }

        // Scanned PDF — render each page to an image and OCR it
        log.info("PDFBox extracted < 30 chars, switching to Tess4J for: " + pdfFile.getName());
        StringBuilder sb = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage pageImage = renderer.renderImageWithDPI(i, 300);
                File tmpImg = File.createTempFile("ocr_page_" + i + "_", ".png");
                ImageIO.write(pageImage, "PNG", tmpImg);
                sb.append(runTesseract(tmpImg));
                sb.append("\n");
                tmpImg.deleteOnExit();
            }
        }
        return sb.toString().trim();
    }

    /**
     * Run Tesseract OCR on a single image file.
     */
    private String runTesseract(File imageFile) throws TesseractException {
        Tesseract tess = new Tesseract();
        tess.setDatapath(tessDataPath);
        tess.setLanguage(tessLanguage);
        return tess.doOCR(imageFile).trim();
    }

    private String determineEngine(File file, String mimeType, String text) throws IOException {
        if (!"application/pdf".equalsIgnoreCase(mimeType)) return "Tess4J";
        try (PDDocument doc = Loader.loadPDF(file)) {
            String raw = new PDFTextStripper().getText(doc).trim();
            return (raw.length() >= 30) ? "PDFBox" : "Tess4J";
        }
    }

    // ----------------------------------------------------------------
    //  Invoice field extraction — regex rules
    // ----------------------------------------------------------------

    private String extractInvoiceNumber(String text) {
        // Matches: "Invoice No: INV-2024-001", "Invoice #12345", "Bill No. 9876"
        Pattern p = Pattern.compile(
            "(?i)(?:invoice\\s*(?:no|number|#|num)[.:# ]*|bill\\s*(?:no|number)[.:# ]*)([A-Z0-9\\-/]{3,20})",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractVendorName(String text) {
        // Matches: "From: Acme Corp", "Vendor: XYZ Ltd", "Sold by: ...", "Bill From: ..."
        Pattern p = Pattern.compile(
            "(?i)(?:from|vendor|seller|sold\\s*by|bill\\s*from)[:\\s]+([A-Za-z0-9 &.,'-]{3,50})",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1).trim();

        // Fallback: first capitalised line that looks like a company name
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.matches("[A-Z][A-Za-z0-9 &.,'-]{2,40}(?:Ltd|LLC|Inc|Corp|Co|Pvt|GmbH|\\.)?.*")
                    && line.length() <= 50) {
                return line;
            }
        }
        return null;
    }

    private String extractInvoiceDate(String text) {
        // Matches: DD/MM/YYYY, MM-DD-YYYY, Month DD YYYY, YYYY-MM-DD
        Pattern p = Pattern.compile(
            "(?i)(?:date|invoice\\s*date|bill\\s*date|dated)[:\\s]+" +
            "(\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}" +
            "|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s*\\d{1,2},?\\s*\\d{4}" +
            "|\\d{4}[/\\-]\\d{2}[/\\-]\\d{2})",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private String extractTotalAmount(String text) {
        // Matches: "Total: $1,234.56", "Amount Due: ₹5000", "Grand Total 2500.00"
        Pattern p = Pattern.compile(
            "(?i)(?:total\\s*(?:amount|due|payable)?|grand\\s*total|amount\\s*due|net\\s*payable)" +
            "\\s*[:\\s]*([\\$₹€£]?\\s*[\\d,]+(?:\\.\\d{1,2})?)",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
