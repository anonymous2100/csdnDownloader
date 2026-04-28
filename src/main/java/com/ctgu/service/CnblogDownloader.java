package com.ctgu.service;

import com.ctgu.entity.DownloadResult;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 博客园专栏文章下载工具
 * <p>
 * 用法：直接运行 main 方法，或调用 {@link #downloadCategory(String, String)} 方法。
 * <p>
 * 示例：
 * <pre>
 *   CnblogDownloader.downloadCategory(
 *       "https://www.cnblogs.com/wmyskxz/category/2494720.html",
 *       "D:/output"
 *   );
 * </pre>
 */
public class CnblogDownloader
{
  /**
   * 请求超时（毫秒）
   */
  private static final int TIMEOUT_MS = 15_000;
  /**
   * User-Agent，模拟浏览器，避免被拦截
   */
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " + "AppleWebKit/537.36 (KHTML, like Gecko) " + "Chrome/120.0.0.0 Safari/537.36";

  public static void main(String[] args) throws Exception
  {
    String categoryUrl = "https://www.cnblogs.com/wmyskxz/category/2494720.html";
    String outputDir = "cnblog_output"; // 相对路径，运行目录下
    downloadCategory(categoryUrl, outputDir);
  }

  /**
   * 下载整个专栏，所有分页的文章都会被下载。
   *
   * @param categoryUrl 专栏首页 URL
   * @param outputDir   输出目录（不存在会自动创建）
   */
  public static void downloadCategory(String categoryUrl, String outputDir) throws Exception
  {
    Files.createDirectories(Paths.get(outputDir));
    System.out.println("输出目录：" + Paths.get(outputDir).toAbsolutePath());

    List<ArticleLink> links = fetchAllArticleLinks(categoryUrl);
    System.out.println("共发现文章：" + links.size() + " 篇\n");

    int success = 0, fail = 0;
    for(int i = 0; i < links.size(); i++)
    {
      ArticleLink link = links.get(i);
      System.out.printf("[%d/%d] 正在下载：%s%n", i + 1, links.size(), link.title);
      try
      {
        String html = fetchHtml(link.url);
        String mdBody = extractArticleAsMarkdown(html);
        String title = sanitizeFilename(link.title);
        String baseName = String.format("%03d_%s", i + 1, title);

        // 保存 Markdown
        String mdContent = "# " + link.title + "\n\n" + "> 原文：" + link.url + "\n\n" + mdBody;
        String mdPath = outputDir + File.separator + baseName + ".md";
        writeFile(mdPath, mdContent);
        System.out.println("       已保存 MD  → " + baseName + ".md");

        // 保存 PDF
        String pdfPath = outputDir + File.separator + baseName + ".pdf";
        writePdf(pdfPath, link.title, link.url, mdBody);
        System.out.println("       已保存 PDF → " + baseName + ".pdf");

        success++;
      }
      catch(Exception e)
      {
        System.err.println("       [FAILED] " + e.getMessage());
        fail++;
      }
      // 礼貌性延迟，避免频繁请求
      Thread.sleep(800);
    }
    System.out.printf("%n完成！成功 %d 篇，失败 %d 篇。%n", success, fail);
  }

  // -------------------------------------------------------------------------
  // 1. 抓取专栏所有文章链接（支持分页）
  // -------------------------------------------------------------------------

  private static List<ArticleLink> fetchAllArticleLinks(String categoryUrl) throws Exception
  {
    List<ArticleLink> all = new ArrayList<>();
    String pageUrl = categoryUrl;

    while(pageUrl != null)
    {
      System.out.println("正在解析列表页：" + pageUrl);
      String html = fetchHtml(pageUrl);
      all.addAll(parseArticleList(html));
      pageUrl = parseNextPage(html, pageUrl);
      Thread.sleep(500);
    }
    return all;
  }

  /**
   * 从列表页 HTML 中解析文章标题 + URL。
   * 博客园专栏列表中文章链接形如：
   * &lt;a class="entrylistItemTitle" href="https://www.cnblogs.com/xxx/p/12345"&gt;
   * &lt;span role="heading" aria-level="2"&gt;标题&lt;/span&gt;
   * &lt;/a&gt;
   */
  private static List<ArticleLink> parseArticleList(String html)
  {
    List<ArticleLink> list = new ArrayList<>();
    // 第一步：匹配整个 <a class="entrylistItemTitle" ...>...</a> 块
    Pattern blockPattern =
        Pattern.compile("<a\\b[^>]*class=\"entrylistItemTitle\"[^>]*>.*?</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    // 第二步：从块中提取 href
    Pattern hrefPattern = Pattern.compile("href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    Matcher blockMatcher = blockPattern.matcher(html);
    while(blockMatcher.find())
    {
      String block = blockMatcher.group();
      Matcher hrefMatcher = hrefPattern.matcher(block);
      if(hrefMatcher.find())
      {
        String url = hrefMatcher.group(1).trim();
        // 提取 <a> 标签内的文本（去掉所有子标签）
        String inner = block.replaceAll("(?si)^<a[^>]*>|</a>$", "");
        String title = stripTags(inner).trim();
        if(!url.isEmpty() && !title.isEmpty())
        {
          list.add(new ArticleLink(title, url));
        }
      }
    }
    return list;
  }

  /**
   * 解析下一页 URL。
   * 博客园分页：&lt;a href="?page=2"&gt;下一页&lt;/a&gt; 或绝对地址。
   */
  private static String parseNextPage(String html, String currentUrl)
  {
    // 匹配 "下一页" 链接
    Pattern p = Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>\\s*下一页\\s*</a>", Pattern.CASE_INSENSITIVE);
    Matcher m = p.matcher(html);
    if(m.find())
    {
      String href = m.group(1).trim();
      if(href.startsWith("http"))
      {
        return href;
      }
      else
      {
        // 相对路径处理
        try
        {
          URL base = new URL(currentUrl);
          return new URL(base, href).toString();
        }
        catch(Exception e)
        {
          return null;
        }
      }
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // 2. 将文章 HTML 转换为 Markdown
  // -------------------------------------------------------------------------

  /**
   * 从文章页 HTML 中提取正文，并做基本的 HTML → Markdown 转换。
   */
  private static String extractArticleAsMarkdown(String html)
  {
    // 提取正文容器 #cnblogs_post_body
    String body = extractBlock(html, "id=\"cnblogs_post_body\"");
    if(body == null || body.isEmpty())
    {
      // 兜底：尝试 <div class="blogpost-body">
      body = extractBlock(html, "class=\"blogpost-body\"");
    }
    if(body == null || body.isEmpty())
    {
      body = html; // 实在找不到就转换全页
    }
    return htmlToMarkdown(body);
  }

  /**
   * 简单的 HTML → Markdown 转换，覆盖常见标签。
   */
  private static String htmlToMarkdown(String html)
  {
    // 去掉 script / style 块
    html = html.replaceAll("(?si)<script[^>]*>.*?</script>", "");
    html = html.replaceAll("(?si)<style[^>]*>.*?</style>", "");

    // 代码块：<pre><code>...</code></pre>  →  ```\n...\n```
    html = html.replaceAll("(?si)<pre[^>]*>\\s*<code[^>]*>(.*?)</code>\\s*</pre>", "\n```\n$1\n```\n");
    // 单独的 <pre>
    html = html.replaceAll("(?si)<pre[^>]*>(.*?)</pre>", "\n```\n$1\n```\n");
    // 行内 code
    html = html.replaceAll("(?si)<code[^>]*>(.*?)</code>", "`$1`");

    // 标题
    for(int lvl = 1; lvl <= 6; lvl++)
    {
      String hashes = repeat("#", lvl);
      html = html.replaceAll("(?si)<h" + lvl + "[^>]*>(.*?)</h" + lvl + ">", "\n" + hashes + " $1\n");
    }

    // 加粗 / 斜体
    html = html.replaceAll("(?si)<strong[^>]*>(.*?)</strong>", "**$1**");
    html = html.replaceAll("(?si)<b[^>]*>(.*?)</b>", "**$1**");
    html = html.replaceAll("(?si)<em[^>]*>(.*?)</em>", "*$1*");
    html = html.replaceAll("(?si)<i[^>]*>(.*?)</i>", "*$1*");
    html = html.replaceAll("(?si)<del[^>]*>(.*?)</del>", "~~$1~~");
    html = html.replaceAll("(?si)<s[^>]*>(.*?)</s>", "~~$1~~");

    // 链接 / 图片
    html = html.replaceAll("(?si)<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");
    html = html.replaceAll("(?si)<img[^>]+src=\"([^\"]+)\"[^>]*alt=\"([^\"]*?)\"[^>]*>", "![$2]($1)");
    html = html.replaceAll("(?si)<img[^>]+src=\"([^\"]+)\"[^>]*>", "![]($1)");

    // 引用块
    html = html.replaceAll("(?si)<blockquote[^>]*>(.*?)</blockquote>", "\n> $1\n");

    // 有序 / 无序列表
    html = html.replaceAll("(?si)<li[^>]*>(.*?)</li>", "\n- $1");
    html = html.replaceAll("(?si)</?[ou]l[^>]*>", "\n");

    // 水平线
    html = html.replaceAll("(?si)<hr[^>]*/?>", "\n---\n");

    // 段落 / 换行
    html = html.replaceAll("(?si)<p[^>]*>", "\n");
    html = html.replaceAll("(?si)</p>", "\n");
    html = html.replaceAll("(?si)<br\\s*/?>", "\n");
    html = html.replaceAll("(?si)<div[^>]*>", "\n");
    html = html.replaceAll("(?si)</div>", "\n");

    // 去掉剩余 HTML 标签
    html = html.replaceAll("<[^>]+>", "");

    // HTML 实体解码
    html = decodeHtmlEntities(html);

    // 整理多余空行
    html = html.replaceAll("(?m)\\n{3,}", "\n\n");
    return html.trim();
  }

  // -------------------------------------------------------------------------
  // 3. 辅助方法
  // -------------------------------------------------------------------------

  /**
   * 获取 URL 的 HTML 内容
   */
  private static String fetchHtml(String urlStr) throws Exception
  {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection)url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(TIMEOUT_MS);
    conn.setReadTimeout(TIMEOUT_MS);
    conn.setRequestProperty("User-Agent", USER_AGENT);
    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
    conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    conn.setInstanceFollowRedirects(true);

    int code = conn.getResponseCode();
    if(code != 200)
    {
      throw new IOException("HTTP " + code + " → " + urlStr);
    }

    // 探测编码
    String contentType = conn.getContentType();
    String charset = "UTF-8";
    if(contentType != null && contentType.contains("charset="))
    {
      charset = contentType.substring(contentType.indexOf("charset=") + 8).trim();
    }

    try (InputStream is = conn.getInputStream(); BufferedReader reader = new BufferedReader(new InputStreamReader(is, charset)))
    {
      StringBuilder sb = new StringBuilder();
      String line;
      while((line = reader.readLine()) != null)
      {
        sb.append(line).append('\n');
      }
      return sb.toString();
    }
  }

  /**
   * 提取以 {@code marker} 属性开头的第一个 &lt;div&gt; 块的内容（支持嵌套）。
   */
  private static String extractBlock(String html, String marker)
  {
    int start = html.indexOf(marker);
    if(start < 0)
      return null;
    // 找到该 div 的 '>' 开始位置
    int tagStart = html.lastIndexOf("<", start);
    if(tagStart < 0)
      return null;
    int tagEnd = html.indexOf(">", start);
    if(tagEnd < 0)
      return null;

    // 从 tagEnd+1 开始，找到匹配的 </div>
    int depth = 1;
    int pos = tagEnd + 1;
    while(pos < html.length() && depth > 0)
    {
      int nextOpen = indexOfIgnoreCase(html, "<div", pos);
      int nextClose = indexOfIgnoreCase(html, "</div>", pos);
      if(nextClose < 0)
        break;
      if(nextOpen >= 0 && nextOpen < nextClose)
      {
        depth++;
        pos = nextOpen + 4;
      }
      else
      {
        depth--;
        if(depth == 0)
        {
          return html.substring(tagEnd + 1, nextClose);
        }
        pos = nextClose + 6;
      }
    }
    return null;
  }

  private static int indexOfIgnoreCase(String src, String target, int from)
  {
    return src.toLowerCase().indexOf(target.toLowerCase(), from);
  }

  /**
   * 去掉字符串中所有 HTML 标签
   */
  private static String stripTags(String html)
  {
    return html == null ? "" : html.replaceAll("<[^>]+>", "").trim();
  }

  /**
   * 解码常用 HTML 实体
   */
  private static String decodeHtmlEntities(String s)
  {
    s = s.replace("&amp;", "&");
    s = s.replace("&lt;", "<");
    s = s.replace("&gt;", ">");
    s = s.replace("&quot;", "\"");
    s = s.replace("&#39;", "'");
    s = s.replace("&apos;", "'");
    s = s.replace("&nbsp;", " ");
    s = s.replace("&mdash;", "—");
    s = s.replace("&ndash;", "–");
    s = s.replace("&hellip;", "…");
    // 数字实体 &#数字;
    Pattern p = Pattern.compile("&#(\\d+);");
    Matcher m = p.matcher(s);
    StringBuffer sb = new StringBuffer();
    while(m.find())
    {
      int code = Integer.parseInt(m.group(1));
      m.appendReplacement(sb, String.valueOf((char)code));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * 将文章标题处理成合法文件名
   */
  private static String sanitizeFilename(String name)
  {
    // 替换 Windows / macOS / Linux 均不允许的字符
    name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
    // 限制长度，避免路径过长
    if(name.length() > 80)
    {
      name = name.substring(0, 80);
    }
    return name.trim();
  }

  /**
   * 重复字符串 n 次（Java 8 兼容写法）
   */
  private static String repeat(String s, int n)
  {
    StringBuilder sb = new StringBuilder();
    for(int i = 0; i < n; i++)
      sb.append(s);
    return sb.toString();
  }

  /**
   * 写文件（UTF-8）
   */
  private static void writeFile(String path, String content) throws IOException
  {
    try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8))
    {
      writer.write(content);
    }
  }

  /**
   * 将文章内容写入 PDF 文件（使用 iText7）。
   * 优先使用系统中文字体，若找不到则降级为 Helvetica（中文可能显示为方块，但不会崩溃）。
   */
  private static void writePdf(String path, String title, String sourceUrl, String mdBody) throws Exception
  {
    PdfFont font = loadCjkFont();
    PdfFont boldFont = loadCjkFont(); // iText7 对同一字体文件可重复加载

    try (PdfWriter pdfWriter = new PdfWriter(path); PdfDocument pdfDoc = new PdfDocument(pdfWriter); Document doc = new Document(pdfDoc))
    {

      // 页边距
      doc.setMargins(50, 50, 50, 50);

      // 标题
      Paragraph titlePara =
          new Paragraph(title).setFont(boldFont).setFontSize(18).setFontColor(ColorConstants.BLACK).setTextAlignment(TextAlignment.LEFT)
              .setMarginBottom(8);
      doc.add(titlePara);

      // 来源链接（灰色小字）
      Paragraph sourcePara = new Paragraph().add(new Text("原文：").setFont(font).setFontSize(9).setFontColor(ColorConstants.GRAY))
          .add(new Text(sourceUrl).setFont(font).setFontSize(9).setFontColor(ColorConstants.BLUE)).setMarginBottom(16);
      doc.add(sourcePara);

      // 分割线（用短横线段落模拟）
      doc.add(new Paragraph(repeat("\u2500", 60)).setFont(font).setFontSize(8).setFontColor(ColorConstants.LIGHT_GRAY).setMarginBottom(12));

      // 正文：按行拆分，识别标题行（# 开头）和普通段落
      String[] lines = mdBody.split("\n");
      StringBuilder paragraphBuf = new StringBuilder();
      for(String line : lines)
      {
        String trimmed = line.trim();
        if(trimmed.isEmpty())
        {
          // 遇到空行，先flush当前buffer
          if(paragraphBuf.length() > 0)
          {
            doc.add(new Paragraph(paragraphBuf.toString()).setFont(font).setFontSize(10).setMultipliedLeading(1.4f).setMarginBottom(6));
            paragraphBuf.setLength(0);
          }
        }
        else if(trimmed.startsWith("# "))
        {
          flushBuffer(doc, font, paragraphBuf);
          doc.add(new Paragraph(trimmed.substring(2)).setFont(boldFont).setFontSize(16).setMarginTop(14).setMarginBottom(6));
        }
        else if(trimmed.startsWith("## "))
        {
          flushBuffer(doc, font, paragraphBuf);
          doc.add(new Paragraph(trimmed.substring(3)).setFont(boldFont).setFontSize(14).setMarginTop(12).setMarginBottom(5));
        }
        else if(trimmed.startsWith("### "))
        {
          flushBuffer(doc, font, paragraphBuf);
          doc.add(new Paragraph(trimmed.substring(4)).setFont(boldFont).setFontSize(12).setMarginTop(10).setMarginBottom(4));
        }
        else if(trimmed.startsWith("#### ") || trimmed.startsWith("##### ") || trimmed.startsWith("###### "))
        {
          flushBuffer(doc, font, paragraphBuf);
          int spaceIdx = trimmed.indexOf(' ');
          doc.add(new Paragraph(trimmed.substring(spaceIdx + 1)).setFont(boldFont).setFontSize(11).setMarginTop(8).setMarginBottom(4));
        }
        else if(trimmed.startsWith("```"))
        {
          // 代码块标记行跳过（内容会在buffer中）
          flushBuffer(doc, font, paragraphBuf);
        }
        else if(trimmed.startsWith("---"))
        {
          flushBuffer(doc, font, paragraphBuf);
          doc.add(
              new Paragraph(repeat("\u2500", 60)).setFont(font).setFontSize(8).setFontColor(ColorConstants.LIGHT_GRAY).setMarginBottom(6));
        }
        else if(trimmed.startsWith("- ") || trimmed.startsWith("* "))
        {
          flushBuffer(doc, font, paragraphBuf);
          doc.add(new Paragraph("  • " + trimmed.substring(2)).setFont(font).setFontSize(10).setMultipliedLeading(1.3f).setMarginBottom(2));
        }
        else if(trimmed.startsWith("> "))
        {
          flushBuffer(doc, font, paragraphBuf);
          doc.add(new Paragraph(trimmed.substring(2)).setFont(font).setFontSize(10).setFontColor(ColorConstants.GRAY).setMarginLeft(20)
              .setMultipliedLeading(1.3f).setMarginBottom(4));
        }
        else
        {
          if(paragraphBuf.length() > 0)
            paragraphBuf.append(' ');
          paragraphBuf.append(trimmed);
        }
      }
      flushBuffer(doc, font, paragraphBuf);
    }
  }

  /**
   * 将 StringBuilder 中积累的文本作为段落写入 PDF，然后清空 buffer
   */
  private static void flushBuffer(Document doc, PdfFont font, StringBuilder buf) throws Exception
  {
    if(buf.length() > 0)
    {
      doc.add(new Paragraph(buf.toString()).setFont(font).setFontSize(10).setMultipliedLeading(1.4f).setMarginBottom(6));
      buf.setLength(0);
    }
  }

  /**
   * 加载支持中文的字体。
   * 按优先级依次尝试：Windows 宋体 → macOS PingFang → Linux 文泉驿 → 内置 Helvetica。
   */
  private static PdfFont loadCjkFont() throws Exception
  {
    // iText7 中 ttc 字体用 "path#index" 格式指定字体索引
    String[] candidates = { "C:/Windows/Fonts/simsun.ttc#0",   // Windows 宋体
        "C:/Windows/Fonts/msyh.ttc#0",     // Windows 微软雅黑
        "C:/Windows/Fonts/simhei.ttf",      // Windows 黑体
        "/Library/Fonts/PingFang.ttc#0",    // macOS
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc#0", // Linux
    };
    for(String candidate : candidates)
    {
      try
      {
        // 提取实际文件路径（去掉 #index 后缀）
        String filePath = candidate.contains("#") ? candidate.substring(0, candidate.indexOf('#')) : candidate;
        File f = new File(filePath);
        if(f.exists())
        {
          // iText7 7.x API: createFont(fontProgram, encoding, EmbeddingStrategy)
          return PdfFontFactory.createFont(candidate, PdfEncodings.IDENTITY_H, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
        }
      }
      catch(Exception ignored)
      {
        // 尝试下一个
      }
    }
    // 最终降级：内置字体（不支持中文，但不会抛异常）
    return PdfFontFactory.createFont();
  }

  private static class ArticleLink
  {
    final String title;
    final String url;

    ArticleLink(String title, String url)
    {
      this.title = title;
      this.url = url;
    }
  }

  // =========================================================================
  //  URL 类型判断（供 DownloaderUI 路由使用）
  // =========================================================================

  /**
   * 是否博客园单篇文章链接。
   * 例：https://www.cnblogs.com/xxx/p/12345678.html
   * https://www.cnblogs.com/xxx/archive/2024/01/01/12345678.html
   */
  public static boolean isCnblogArticleUrl(String url)
  {
    if(url == null)
      return false;
    return url.contains("cnblogs.com") && (url.matches(".*cnblogs\\.com/[^/]+/p/\\d+.*") || url.matches(
        ".*cnblogs\\.com/[^/]+/archive/.*"));
  }

  /**
   * 是否博客园专栏（分类）链接。
   * 例：https://www.cnblogs.com/xxx/category/2494720.html
   */
  public static boolean isCnblogCategoryUrl(String url)
  {
    if(url == null)
      return false;
    return url.contains("cnblogs.com") && url.contains("/category/");
  }

  /**
   * 是否博客园相关链接（单篇文章 或 专栏）。
   */
  public static boolean isCnblogUrl(String url)
  {
    return isCnblogArticleUrl(url) || isCnblogCategoryUrl(url);
  }

  // =========================================================================
  //  专栏：获取全部文章链接（供 UI 展开任务列表使用）
  // =========================================================================

  /**
   * 获取博客园专栏下所有文章的 URL 列表，支持多页翻页。
   *
   * @param categoryUrl 专栏首页 URL
   * @return 文章链接列表（按专栏页面顺序）
   */
  public List<String> fetchCategoryArticleUrls(String categoryUrl) throws Exception
  {
    List<ArticleLink> links = fetchAllArticleLinks(categoryUrl);
    List<String> urls = new ArrayList<>();
    for(ArticleLink link : links)
    {
      urls.add(link.url);
    }
    return urls;
  }

  // =========================================================================
  //  单篇文章下载 → DownloadResult（供 DownloaderUI 统一调度使用）
  // =========================================================================

  /**
   * 下载单篇博客园文章，返回与 CSDNDownloader 相同格式的 {@link DownloadResult}。
   *
   * @param articleUrl 文章 URL
   * @return 包含 HTML 内容和元数据的 DownloadResult
   */
  public DownloadResult downloadArticle(String articleUrl)
  {
    long start = System.currentTimeMillis();
    try
    {
      String rawHtml = fetchHtml(articleUrl);

      // 提取标题
      String title = extractPageTitle(rawHtml);

      // 提取正文并转 Markdown
      String mdBody = extractArticleAsMarkdown(rawHtml);
      String mdContent = "# " + title + "\n\n> 原文：" + articleUrl + "\n\n" + mdBody;

      // 包装成统一 HTML（复用内置模板）
      String wrappedHtml = wrapAsHtml(title, articleUrl, mdContent);

      long elapsed = System.currentTimeMillis() - start;
      return DownloadResult.builder().success(true).url(articleUrl).title(title).html(wrappedHtml).contentLength(wrappedHtml.length())
          .downloadTime(elapsed).build();
    }
    catch(Exception e)
    {
      return DownloadResult.createErrorResult(articleUrl, e.getMessage(), 500);
    }
  }

  /**
   * 从 HTML 中提取 &lt;title&gt; 并去掉博客园后缀
   */
  private static String extractPageTitle(String html)
  {
    Pattern p = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    Matcher m = p.matcher(html);
    if(m.find())
    {
      String t = stripTags(m.group(1)).trim();
      t = t.replaceAll("\\s*-\\s*博客园\\s*$", "").trim();
      return t.isEmpty() ? "未知标题" : t;
    }
    return "未知标题";
  }

  /**
   * 将 Markdown 正文包装成可直接保存 / 生成 PDF 的 HTML 字符串
   */
  private static String wrapAsHtml(String title, String sourceUrl, String mdContent)
  {
    String style =
        "body{font-family:'PingFang SC','Microsoft YaHei',SimHei,sans-serif;" + "line-height:1.6;padding:20px;background:#f6f8fa;}"
            + ".paper{max-width:900px;margin:0 auto;background:#fff;padding:40px;" + "box-shadow:0 2px 12px 0 rgba(0,0,0,0.1);}"
            + "h1{font-size:24px;color:#2c3e50;border-bottom:1px solid #eaecef;padding-bottom:10px;}"
            + "a{color:#0366d6;text-decoration:none;}"
            + "blockquote{border-left:4px solid #dfe2e5;color:#6a737d;padding-left:10px;margin:10px 0;}"
            + "code{font-family:Consolas,Monaco,monospace;background:rgba(27,31,35,0.05);" + "padding:0.2em 0.4em;border-radius:3px;}"
            + "pre{background:#282c34;color:#abb2bf;padding:15px;border-radius:5px;"
            + "overflow-x:auto;white-space:pre-wrap;word-break:break-all;}"
            + "img{max-width:95%;height:auto;display:block;margin:15px auto;border-radius:4px;}"
            + "*{font-family:'MyChineseFont',sans-serif !important;}";

    StringBuilder sb = new StringBuilder();
    sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'><title>").append(escHtml(title)).append("</title><style>")
        .append(style).append("</style></head><body><div class='paper'>").append("<h1>").append(escHtml(title)).append("</h1>")
        .append("<div style='color:#888;font-size:12px;margin-bottom:20px;'>").append("原文链接: <a href='").append(escHtml(sourceUrl))
        .append("'>").append(escHtml(sourceUrl)).append("</a></div>").append("<div id='content'>");

    // 简单 Markdown → HTML 渲染
    String[] lines = mdContent.split("\n");
    boolean inCode = false;
    for(String line : lines)
    {
      String trimmed = line.trim();
      if(trimmed.startsWith("```"))
      {
        sb.append(inCode ? "</code></pre>\n" : "<pre><code>");
        inCode = !inCode;
        continue;
      }
      if(inCode)
      {
        sb.append(escHtml(line)).append("\n");
        continue;
      }
      if(trimmed.startsWith("# "))
        sb.append("<h1>").append(escHtml(trimmed.substring(2))).append("</h1>\n");
      else if(trimmed.startsWith("## "))
        sb.append("<h2>").append(escHtml(trimmed.substring(3))).append("</h2>\n");
      else if(trimmed.startsWith("### "))
        sb.append("<h3>").append(escHtml(trimmed.substring(4))).append("</h3>\n");
      else if(trimmed.startsWith("> "))
        sb.append("<blockquote>").append(escHtml(trimmed.substring(2))).append("</blockquote>\n");
      else if(trimmed.startsWith("- ") || trimmed.startsWith("* "))
        sb.append("<li>").append(escHtml(trimmed.substring(2))).append("</li>\n");
      else if(trimmed.startsWith("---"))
        sb.append("<hr/>\n");
      else if(trimmed.isEmpty())
        sb.append("<br/>\n");
      else
        sb.append("<p>").append(escHtml(trimmed)).append("</p>\n");
    }
    if(inCode)
      sb.append("</code></pre>\n");
    sb.append("</div></div></body></html>");
    return sb.toString();
  }

  private static String escHtml(String s)
  {
    if(s == null)
      return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
  }
}
