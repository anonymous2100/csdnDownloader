package com.ctgu.entity;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;


/**
 * @author lh2
 * @version 1.0
 * @description: 下载结果实体类
 * @date 2026-01-03 14:42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadResult {
    /**
     * 是否下载成功
     */
    private boolean success;
    /**
     * 文章原始 URL
     */
    private String url;
    /**
     * 文章标题
     */
    private String title;
    /**
     * 错误信息（下载失败时记录）
     */
    private String error;
    /**
     * 清洗后的 HTML 正文内容
     */
    private String html;
    /**
     * HTTP 响应状态码，默认为 200
     */
    @Builder.Default
    private int httpStatus = 200;
    /**
     * 内容类型（如 text/html）
     */
    private String contentType;
    /**
     * 下载及处理的总耗时（毫秒）
     */
    @Builder.Default
    private long downloadTime = 0;
    /**
     * 正文内容的字符长度
     */
    @Builder.Default
    private int contentLength = 0;
    /**
     * 文章是否存在（用于区分 404 错误与网络异常）
     */
    @Builder.Default
    private boolean articleExists = true;
    /**
     * CSDN 文章唯一标识 ID
     */
    private String articleId;
    /**
     * 记录生成的时间点
     */
    @Builder.Default
    private Date downloadDate = new Date();

    public DownloadResult(boolean success, String url, String title, String error, String html) {
        this.success = success;
        this.url = url;
        this.title = title;
        this.error = error;
        this.html = html;
        this.downloadDate = new Date();
        // 默认认为文章存在
        this.articleExists = true;
    }

    public DownloadResult(boolean success, String url, String title, String error, String html, int httpStatus, String contentType, long downloadTime, int contentLength, boolean articleExists, String articleId) {
        this.success = success;
        this.url = url;
        this.title = title;
        this.error = error;
        this.html = html;
        this.httpStatus = httpStatus;
        this.contentType = contentType;
        this.downloadTime = downloadTime;
        this.contentLength = contentLength;
        this.articleExists = articleExists;
        this.articleId = articleId;
        this.downloadDate = new Date();
    }

    /**
     * 辅助静态方法：快速创建失败的结果对象
     *
     * @param url        失败的链接
     * @param error      失败原因描述
     * @param httpStatus 对应的HTTP状态码
     * @return 包含错误信息的 DownloadResult 实例
     */
    public static DownloadResult createErrorResult(String url, String error, int httpStatus) {
        return DownloadResult.builder()
                .success(false)
                .url(url)
                .error(error)
                .httpStatus(httpStatus)
                .articleExists(httpStatus != 404)
                .build();
    }
}