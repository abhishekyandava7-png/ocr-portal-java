package com.ocrportal.controller;

import com.ocrportal.model.DocumentRecord;
import com.ocrportal.repository.DocumentRepository;
import com.ocrportal.service.ExportService;
import com.ocrportal.service.OcrService;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * Main MVC controller:
 *  GET  /               → Dashboard / Library
 *  POST /upload         → Process uploaded file
 *  GET  /document/{id}  → Detail view
 *  POST /document/{id}  → Save edits
 *  POST /document/{id}/delete → Delete record
 *  GET  /export/csv     → Download CSV
 *  GET  /export/excel   → Download XLSX
 *  GET  /export/pdf     → Download PDF
 *  GET  /file/{id}      → Serve the raw uploaded file (for inline preview)
 */
@Controller
public class PortalController {

    private final DocumentRepository repository;
    private final OcrService ocrService;
    private final ExportService exportService;

    public PortalController(DocumentRepository repository,
                            OcrService ocrService,
                            ExportService exportService) {
        this.repository    = repository;
        this.ocrService    = ocrService;
        this.exportService = exportService;
    }

    // ----------------------------------------------------------------
    //  Dashboard / Library (index)
    // ----------------------------------------------------------------

    @GetMapping("/")
    public String dashboard(@RequestParam(value = "q", required = false) String query, Model model) {

        List<DocumentRecord> docs;
        if (query != null && !query.isBlank()) {
            docs = repository.search(query.trim());
        } else {
            docs = repository.findAll(Sort.by(Sort.Direction.DESC, "uploadedAt"));
        }

        // Stats
        long total     = repository.count();
        long completed = repository.countByStatus("COMPLETED");
        long failed    = repository.countByStatus("FAILED");
        long needsReview = repository.countByReviewedFalseAndStatus("COMPLETED");

        model.addAttribute("docs",        docs);
        model.addAttribute("query",       query);
        model.addAttribute("total",       total);
        model.addAttribute("completed",   completed);
        model.addAttribute("failed",      failed);
        model.addAttribute("needsReview", needsReview);

        return "index";
    }

    // ----------------------------------------------------------------
    //  File Upload
    // ----------------------------------------------------------------

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMsg", "Please select a file to upload.");
            return "redirect:/";
        }
        String contentType = file.getContentType();
        if (contentType == null ||
            (!contentType.equalsIgnoreCase("application/pdf") &&
             !contentType.startsWith("image/"))) {
            redirectAttributes.addFlashAttribute("errorMsg",
                "Only PDF, PNG, and JPEG files are supported.");
            return "redirect:/";
        }
        try {
            DocumentRecord doc = ocrService.processUpload(file);
            redirectAttributes.addFlashAttribute("successMsg",
                "✅ \"" + doc.getFilename() + "\" processed successfully!");
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("errorMsg",
                "Upload failed: " + e.getMessage());
        }
        return "redirect:/";
    }

    // ----------------------------------------------------------------
    //  Document Detail View
    // ----------------------------------------------------------------

    @GetMapping("/document/{id}")
    public String detail(@PathVariable Long id, Model model) {
        DocumentRecord doc = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        model.addAttribute("doc", doc);
        return "detail";
    }

    // ----------------------------------------------------------------
    //  Save Edited Fields
    // ----------------------------------------------------------------

    @PostMapping("/document/{id}")
    public String save(@PathVariable Long id,
                       @RequestParam(value = "invoiceNumber", required = false) String invoiceNumber,
                       @RequestParam(value = "vendorName",    required = false) String vendorName,
                       @RequestParam(value = "invoiceDate",   required = false) String invoiceDate,
                       @RequestParam(value = "totalAmount",   required = false) String totalAmount,
                       @RequestParam(value = "rawText",       required = false) String rawText,
                       @RequestParam(value = "reviewed",      defaultValue = "false") boolean reviewed,
                       RedirectAttributes redirectAttributes) {

        DocumentRecord doc = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));

        doc.setInvoiceNumber(invoiceNumber);
        doc.setVendorName(vendorName);
        doc.setInvoiceDate(invoiceDate);
        doc.setTotalAmount(totalAmount);
        doc.setRawText(rawText);
        doc.setReviewed(reviewed);
        repository.save(doc);

        redirectAttributes.addFlashAttribute("successMsg", "✅ Changes saved successfully.");
        return "redirect:/document/" + id;
    }

    // ----------------------------------------------------------------
    //  Delete Document
    // ----------------------------------------------------------------

    @PostMapping("/document/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        repository.findById(id).ifPresent(doc -> {
            // Also remove the file from disk
            File f = new File(doc.getStoredPath());
            if (f.exists()) f.delete();
            repository.deleteById(id);
        });
        redirectAttributes.addFlashAttribute("successMsg", "🗑️ Document deleted.");
        return "redirect:/";
    }

    // ----------------------------------------------------------------
    //  Serve raw file for inline preview (PDF / image)
    // ----------------------------------------------------------------

    @GetMapping("/file/{id}")
    public ResponseEntity<byte[]> serveFile(@PathVariable Long id) throws IOException {
        DocumentRecord doc = repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        File file = new File(doc.getStoredPath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try { mediaType = MediaType.parseMediaType(doc.getMimeType()); } catch (Exception ignored) {}

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.getFilename() + "\"")
            .contentType(mediaType)
            .body(bytes);
    }

    // ----------------------------------------------------------------
    //  Export Endpoints
    // ----------------------------------------------------------------

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv() throws IOException {
        List<DocumentRecord> docs = repository.findAll(Sort.by(Sort.Direction.DESC, "uploadedAt"));
        byte[] data = exportService.exportCsv(docs);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ocr-export.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(data);
    }

    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel() throws IOException {
        List<DocumentRecord> docs = repository.findAll(Sort.by(Sort.Direction.DESC, "uploadedAt"));
        byte[] data = exportService.exportExcel(docs);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"ocr-export.xlsx\"")
            .contentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(data);
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf() throws IOException {
        List<DocumentRecord> docs = repository.findAll(Sort.by(Sort.Direction.DESC, "uploadedAt"));
        byte[] data = exportService.exportPdf(docs);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ocr-report.pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(data);
    }
}

