package com.ocrportal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "app.upload-dir=./test-uploads",
    "tesseract.data-path=C:/Program Files/Tesseract-OCR/tessdata",
    "tesseract.language=eng"
})
class OcrPortalApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts cleanly with an in-memory H2 DB
    }
}

