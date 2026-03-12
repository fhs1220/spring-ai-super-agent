package com.fhs.aiagent.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PDFGenerationToolTest {

    @Test
    void generatePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "Spring AI 官方文档.pdf";
        String content = "Spring AI 官方文档:\n" +
                "https://spring.io/projects/spring-ai";
        String result = tool.generatePDF(fileName, content);
        assertNotNull(result);
    }
}