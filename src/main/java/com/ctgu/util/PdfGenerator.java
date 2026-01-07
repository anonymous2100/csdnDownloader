package com.ctgu.util;


import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author lh2
 * @version 1.0
 * @description: PDF 生成工具类， 基于 openhtmltopdf 库将 XHTML 内容转换为 PDF 文档，处理中文字体映射及 HTML 实体转义兼容性问题
 * @date 2026-01-03 14:42
 */
@Slf4j
public class PdfGenerator {
    /**
     * 将 HTML 字符串转换为 PDF 文件
     *
     * @param html       符合 XHTML 规范的 HTML 字符串
     * @param outputPath PDF 文件保存的绝对路径
     * @throws Exception 当字体缺失或 IO 异常时抛出
     **/
    public static void generate(String html, String outputPath) throws Exception {
        // 1. 规范化HTML 为 XHTML
        Document doc = Jsoup.parse(html);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        // 替换 PDF 不支持的实体
        String xhtml = doc.html()
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
        try (OutputStream os = new FileOutputStream(outputPath)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // 2. 加载字体
            boolean fontLoaded = false;
            try (InputStream is = PdfGenerator.class.getResourceAsStream("/fonts/Alibaba-PuHuiTi-Regular.ttf")) {
                if (is != null) {
                    byte[] fontBytes = org.apache.commons.io.IOUtils.toByteArray(is);
                    builder.useFont(() -> new ByteArrayInputStream(fontBytes), "MyChineseFont",
                            400, BaseRendererBuilder.FontStyle.NORMAL, true);
                    fontLoaded = true;
                }
            } catch (Exception e) {
                System.err.println("自定义字体加载失败，尝试使用后备方案: " + e.getMessage());
            }
            // 如果没有找到自定义字体，PDFBox 可能会因为缺字体不显示中文 , 这里可以选择去加载系统字体，或仅依赖标准字体
            if (!fontLoaded) {
                // 尝试加载常见的系统字体作为后备 (Windows示例)
                // builder.useFont(new File("C:\\Windows\\Fonts\\simhei.ttf"), "MyChineseFont");
                System.err.println("警告: 未加载中文字体，PDF中文可能显示乱码。请确保 resources/fonts 目录下存在字体文件。");
            }
            builder.withHtmlContent(xhtml, null);
            builder.toStream(os);
            builder.run();
        }
    }
}