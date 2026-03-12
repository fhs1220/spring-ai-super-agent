package com.fhs.aiagent.tools;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 网页抓取工具
 */
public class WebScrapingTool {

    private static final int MAX_CONTENT_LENGTH = 2000;

    @Tool(description = "Scrape useful information from a web page")
    public String scrapeWebPage(
            @ToolParam(description = "URL of the web page to scrape") String url) {

        try {

            // 1. 请求网页
            Document document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(10000)
                    .get();

            // 2. 获取标题
            String title = document.title();

            // 3. 获取描述
            String description = document
                    .select("meta[name=description]")
                    .attr("content");

            // 4. 优先提取正文区域
            Element article = document.selectFirst("article");

            if (article == null) {
                article = document.selectFirst("main");
            }

            if (article == null) {
                article = document.body();
            }

            String content = article.text();

            // 5. 清理常见导航噪声
            content = cleanNoise(content);

            // 6. 限制长度，防止token过多
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }

            // 7. 返回结构化信息
            return """
                    Title: %s
                    Description: %s
                    Content: %s
                    Source: %s
                    """.formatted(
                    safe(title),
                    safe(description),
                    safe(content),
                    url
            );

        } catch (Exception e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }

    /**
     * 清理网页噪声文本
     */
    private String cleanNoise(String text) {

        return text
                .replaceAll("主页|登录|注册|导航|菜单|排行榜|热门话题|APP|会员", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 防止 null
     */
    private String safe(String text) {
        return text == null ? "" : text;
    }
}