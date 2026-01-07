package com.ctgu.service;

import com.ctgu.entity.DownloadResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;


/**
 * @author lh2
 * @version 1.0
 * @description: csdn文章下载器
 * @date 2026-01-03 14:42
 */
@Data
@Slf4j
public class CSDNDownloader {
    private Map<String, String> cookies = new HashMap<>();
    // 使用静态变量缓存Cookie，避免频繁IO
    private static final Map<String, String> cachedCookies = new HashMap<>();
    private static boolean cookiesLoaded = false;
    //加载配置
    private String configFileName = "config.properties";
    private int timeout;
    private String savePath;
    private String userAgent;
    private String uaBot;
    /**
     * 内置默认 HTML 模板
     * 当外部模板文件读取失败时使用
     **/
    private static final String DEFAULT_TEMPLATE = "<!DOCTYPE html>" + "<html lang='zh-CN'>" + "<head><meta charset='UTF-8'><title>{{title}}</title>" + "<style>" + "  body { font-family: 'PingFang SC', 'Microsoft YaHei', SimHei, sans-serif; line-height: 1.6; padding: 20px; background-color: #f6f8fa; }" + "  .paper { max-width: 900px; margin: 0 auto; background: #fff; padding: 40px; box-shadow: 0 2px 12px 0 rgba(0,0,0,0.1); }" + "  h1 { font-size: 24px; color: #2c3e50; border-bottom: 1px solid #eaecef; padding-bottom: 10px; }" + "  a { color: #0366d6; text-decoration: none; }" + "  blockquote { border-left: 4px solid #dfe2e5; color: #6a737d; padding-left: 10px; margin: 10px 0; }" + "  code { font-family: Consolas, Monaco, monospace; background: rgba(27,31,35,0.05); padding: 0.2em 0.4em; border-radius: 3px; }" + "  pre { background: #282c34; color: #abb2bf; padding: 15px; border-radius: 5px; overflow-x: auto; }" + "  * { font-family: 'MyChineseFont', sans-serif !important; }" + "</style>" + "</head>" + "<body>" + "  <div class='paper'>" + "    <h1>{{title}}</h1>" + "    <div style='color: #888; font-size: 12px; margin-bottom: 20px;'>原文链接: <a href='{{url}}'>{{url}}</a></div>" + "    <div id='content'>{{content}}</div>" + "  </div>" + "</body></html>";

    public CSDNDownloader() {
        // 构造时仅在未加载过Cookie时读取
        ensureCookiesLoaded();
        loadConfig();
    }

    /**
     * 加载配置信息
     * 具体逻辑：
     * 1. 优先加载 JAR 包内的默认配置 (src/main/resources/config.properties)
     * 2. 如果当前运行目录下存在物理配置文件，则覆盖默认配置（便于用户自定义）
     **/
    private void loadConfig() {
        Properties props = new Properties();
        try {
            // 步骤 1：加载类路径下的内置默认配置
            try (InputStream is = getClass().getResourceAsStream("/" + configFileName)) {
                if (is != null) {
                    props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                    log.info("加载内置默认配置成功");
                }
            }
            // 步骤 2：尝试加载外部物理配置文件（如果存在则覆盖内置配置）
            File externalFile = new File(configFileName);
            if (externalFile.exists()) {
                try (FileInputStream fis = new FileInputStream(externalFile)) {
                    props.load(new InputStreamReader(fis, StandardCharsets.UTF_8));
                    log.info("检测到外部配置文件，已覆盖默认设置");
                }
            }
            // 步骤 3：解析并赋值（增加 trim() 防止配置文件中多余的空格导致报错）
            // 解析输出目录
            String dir = props.getProperty("output.dir");
            if (dir != null && !dir.trim().isEmpty()) {
                this.savePath = dir.trim();
            }
            // 解析超时时间
            String timeoutSeconds = props.getProperty("timeout.seconds");
            if (timeoutSeconds != null) {
                try {
                    this.timeout = Integer.parseInt(timeoutSeconds.trim()) * 1000; // 转换为毫秒
                } catch (NumberFormatException e) {
                    log.warn("timeout.seconds 格式错误，使用默认值");
                }
            }
            // 解析 User-Agent
            this.userAgent = props.getProperty("user.agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            // 解析 Bot User-Agent
            this.uaBot = props.getProperty("ua.bot",
                    "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
            log.info("配置加载完成：savePath={}, timeout={}", savePath, timeout);
        } catch (IOException e) {
            log.error("加载配置文件过程中发生异常", e);
        }
    }

    private synchronized void ensureCookiesLoaded() {
        if (cookiesLoaded) {
            return;
        }
        File cookieFile = new File("cookie.txt");
        if (cookieFile.exists()) {
            try {
                String rawCookie = FileUtils.readFileToString(cookieFile, StandardCharsets.UTF_8);
                // 简单的解析逻辑，兼容直接复制的Header字符串
                if (rawCookie.contains("=")) {
                    String[] parts = rawCookie.split(";");
                    for (String part : parts) {
                        String[] kv = part.split("=", 2);
                        if (kv.length == 2) {
                            cachedCookies.put(kv[0].trim(), kv[1].trim());
                        }
                    }
                }
                log.info("Cookie加载成功，共 {} 条", cachedCookies.size());
            } catch (IOException e) {
                log.error("读取Cookie文件失败:{}", e.getLocalizedMessage());
            }
        }
        cookiesLoaded = true;
    }

    /**
     * 核心方法：执行文章下载与内容解析
     *
     */
    public DownloadResult downloadArticle(String url) {
        try {
            // 1. 尝试正常访问
            Connection conn = Jsoup.connect(url).userAgent(userAgent).timeout(timeout).referrer("https://blog.csdn.net/");
            if (!cachedCookies.isEmpty()) {
                conn.cookies(cachedCookies);
            }
            Document doc = conn.get();
            String title = doc.title().replace("-CSDN博客", "").trim();
            // 2. 检测是否被折叠或需要关注 (反爬策略)，比如某些防火墙拦截页
            boolean isRestricted = doc.select("#content_views").isEmpty() || doc.html().contains("hide-article-box") || title.contains("Custom-Access-Control");
            if (isRestricted) {
                log.info("检测到内容受限，尝试切换为爬虫模式: {}", url);
                doc = Jsoup.connect(url).userAgent(uaBot).timeout(timeout).get();
            }
            // 3. 统一清洗 HTML
            String cleanHtml = processHtml(doc, url);
            return DownloadResult.builder().success(true).url(url).title(title).html(cleanHtml).contentLength(cleanHtml.length()).build();
        } catch (Exception e) {
            log.error("下载失败: {}", url, e);
            return DownloadResult.createErrorResult(url, e.getMessage(), 500);
        }
    }

    /**
     * 统一的 HTML 处理逻辑: HTML 标签过滤与样式注入
     *
     * @param doc
     * @param url
     * @return
     */
    private String processHtml(Document doc, String url) {
        Element content = doc.selectFirst("#content_views");
        if (content == null) {
            content = doc.selectFirst("article");
        }
        if (content == null) {
            return "<div style='color:red'>无法解析正文内容，可能是付费文章或需要VIP。</div>";
        }
        // 移除干扰元素
        content.select("script, iframe, style, .hide-article-box, .btn-readmore, .recommend-box, .opt-box, .template-box").remove();
        // 修复图片显示 (懒加载 -> 真实链接)
        for (Element img : content.select("img")) {
            String src = img.attr("src");
            String dataSrc = img.attr("data-src");
            if (!dataSrc.isEmpty()) {
                src = dataSrc;
            }
            if (src.startsWith("//")) {
                src = "https:" + src;
            }
            img.attr("src", src);
            // 移除可能导致 PDF 生成异常的属性
            img.removeAttr("data-src");
            img.removeAttr("onerror");
            // 强制样式，防止图片溢出 PDF 页面
            img.attr("style", "max-width: 95%; height: auto; display: block; margin: 15px auto; border-radius: 4px;");
        }
        // 处理代码块，确保 PDF 中换行正常
        for (Element pre : content.select("pre")) {
            pre.attr("style", "white-space: pre-wrap; word-break: break-all; background: #282c34; color: #abb2bf; padding: 10px; border-radius: 5px;");
        }
        String title = doc.title().replace("-CSDN博客", "").trim();
        Element contentElement = doc.selectFirst("#content_views");
        String contentHtml = (contentElement != null) ? contentElement.html() : "内容为空";
        // 从配置中加载模板路径，若无配置则默认为 "template.html"
        String templatePath = "template.html";
        File templateFile = new File(templatePath);
        String finalTemplate = DEFAULT_TEMPLATE; // 默认使用内置的之前样式
        if (templateFile.exists()) {
            try {
                finalTemplate = FileUtils.readFileToString(templateFile, StandardCharsets.UTF_8);
                log.info("成功加载外部模板文件: {}", templatePath);
            } catch (IOException e) {
                log.error("读取外部模板失败，回退到内置模板", e);
            }
        } else {
            log.info("未找到外部模板，使用内置默认样式执行导出");
        }
        // 统一替换占位符
        return finalTemplate.replace("{{title}}", title).replace("{{url}}", url).replace("{{content}}", contentHtml);
    }
}