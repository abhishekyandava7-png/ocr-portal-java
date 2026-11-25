package com.ocrportal.repository;

import com.ocrportal.model.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for DocumentRecord.
 * Custom search query spans filename, invoiceNumber, vendorName, and rawText.
 */
@Repository
public interface DocumentRepository extends JpaRepository<DocumentRecord, Long> {

    /**
     * Full-text search across the most useful fields.
     * Uses LOWER() for case-insensitive matching on H2.
     */
    @Query("""
            SELECT d FROM DocumentRecord d
            WHERE LOWER(d.filename)      LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(d.invoiceNumber) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(d.vendorName)    LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(d.rawText)       LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY d.uploadedAt DESC
            """)
    List<DocumentRecord> search(@Param("q") String query);

    /** Count documents by status (used for dashboard stats) */
    long countByStatus(String status);

    /** Count documents that need human review */
    long countByReviewedFalseAndStatus(String status);
}

