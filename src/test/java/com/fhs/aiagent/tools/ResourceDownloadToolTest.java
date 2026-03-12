package com.fhs.aiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceDownloadToolTest {

    @Test
    public void testDownloadResource() {
        ResourceDownloadTool tool = new ResourceDownloadTool();
        String url = "https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png";
        String fileName = "logo2.png";
        String result = tool.downloadResource(url, fileName);
        assertNotNull(result);
    }
}