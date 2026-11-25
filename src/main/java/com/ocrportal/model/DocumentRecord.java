package com.ocrportal.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA entity representing one uploaded & OCR-processed document.
 * Stored in the H2 file database; persists across application restarts.
 */
@Entity
@Table(name = "document_records")
public class DocumentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Original filename as uploaded by the user */
    private String filename;

    /** Absolute path where the file is saved on disk */
    private String storedPath;

    /** MIME type: application/pdf, image/png, image/jpeg */
    private String mimeType;

    /**
     * Processing status:
     * PENDING → PROCESSING → COMPLETED | FAILED
     */
    private String status;

    /**
     * Which OCR engine was used:
     *  "PDFBox"  - digital PDF text layer
     *  "Tess4J"  - Tesseract for scanned/image content
     *  "None"    - no text found
     */
    private String ocrEngine;

    /** Full raw text extracted by OCR (may be large) */
    @Column(columnDefinition = "TEXT")
    private String rawText;

    // ---- Extracted Invoice Fields ----
    private String invoiceNumber;
    private String vendorName;
    private String invoiceDate;
    private String totalAmount;

    /** true once a user has manually reviewed / corrected the record */
    private boolean reviewed;

    /** Timestamp when the document was first uploaded */
    private LocalDateTime uploadedAt;

    // ---- Constructors ----

    public DocumentRecord() {}

    // ---- Getters & Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOcrEngine() { return ocrEngine; }
    public void setOcrEngine(String ocrEngine) { this.ocrEngine = ocrEngine; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }

    public String getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(String invoiceDate) { this.invoiceDate = invoiceDate; }

    public String getTotalAmount() { return totalAmount; }
    public void setTotalAmount(String totalAmount) { this.totalAmount = totalAmount; }

    public boolean isReviewed() { return reviewed; }
    public void setReviewed(boolean reviewed) { this.reviewed = reviewed; }

    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }

    // ---- Convenience helpers for Thymeleaf ----

    /** Returns true if mimeType is a PDF */
    public boolean isPdf() {
        return mimeType != null && mimeType.equalsIgnoreCase("application/pdf");
    }

    /** Returns true if mimeType is an image */
    public boolean isImage() {
        return mimeType != null && (mimeType.startsWith("image/"));
    }

    /** Short preview of raw text (first 200 chars) */
    public String getTextPreview() {
        if (rawText == null || rawText.isBlank()) return "(no text extracted)";
        return rawText.length() > 200 ? rawText.substring(0, 200) + "…" : rawText;
    }
}

