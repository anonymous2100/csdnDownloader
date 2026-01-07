package com.ctgu.ui;

import com.ctgu.entity.DownloadResult;
import com.ctgu.service.CSDNDownloader;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author lh2
 * @version 1.0
 * @description: csdn下载器主界面
 * @date 2026-01-03 14:42
 */
@Slf4j
public class CSDNDownloaderUI extends JFrame {
    // UI组件
    private JTextArea urlTextArea;
    private JButton downloadButton;
    private JButton stopButton;
    private JButton clearButton;
    private JButton loadFromFileButton;
    private JButton cookieSettingButton;
    private JTable pendingTable;
    private JTable completedTable;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel countLabel;
    private JLabel pathLabel;
    private JTabbedPane tabbedPane;
    private JCheckBox autoPdfCheckBox;
    // 数据模型
    private DefaultTableModel pendingModel;
    private DefaultTableModel completedModel;
    // 下载状态
    private List<String> pendingUrls = new ArrayList<>();
    private List<DownloadResult> completedDownloads = Collections.synchronizedList(new ArrayList<>());
    private Map<String, String> downloadStatusMap = Collections.synchronizedMap(new HashMap<>());
    // 线程控制
    private ExecutorService executorService;
    private List<Future<DownloadResult>> futures = new ArrayList<>();
    private volatile boolean isDownloading = false;
    private AtomicInteger completedCount = new AtomicInteger(0);
    private AtomicInteger successCount = new AtomicInteger(0);
    private AtomicInteger failCount = new AtomicInteger(0);
    private AtomicInteger notFoundCount = new AtomicInteger(0);
    private final String softVersion = "0.5";
    // 自定义配置
    private final String configFileName = "config.properties";
    private String savePath;
    private int maxConcurrentDownloads = 6;
    private long delayBetweenDownloads = 1500;
    private final int DEFAULT_FONT_SIZE = 16;

    public CSDNDownloaderUI() {
        loadConfig();
        initData();
        initUI();
        setupMenu();
    }

    /**
     * 初始化 UI 布局与事件监听
     **/
    private void initUI() {
        setTitle("CSDN博客下载器" + softVersion);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 850);
        setLocationRelativeTo(null);
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        // 1. 顶部面板 (输入区域)
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        topPanel.setBackground(new Color(240, 240, 240));
        // URL输入区
        urlTextArea = new JTextArea(5, 60);
        urlTextArea.setLineWrap(true);
        urlTextArea.setWrapStyleWord(true);
        urlTextArea.setFont(new Font("Consolas", Font.PLAIN, DEFAULT_FONT_SIZE));
        urlTextArea.setToolTipText("请输入CSDN博客URL，每行一个");
        JScrollPane scrollPane = new JScrollPane(urlTextArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder(" 批量URL输入 (每行一个) "));
        scrollPane.setPreferredSize(new Dimension(1000, 120));
        // 按钮区
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        actionPanel.setOpaque(false);
        downloadButton = createStyledButton("开始下载", new Color(0, 120, 215), Color.WHITE);
        stopButton = createStyledButton("停止下载", new Color(220, 53, 69), Color.WHITE);
        stopButton.setEnabled(false);
        clearButton = new JButton("清空列表");
        loadFromFileButton = new JButton("导入文件");
        autoPdfCheckBox = new JCheckBox("同时生成PDF");
        autoPdfCheckBox.setSelected(true);
        cookieSettingButton = new JButton("设置Cookie (解决登录限制)");
        cookieSettingButton.setForeground(new Color(0, 100, 0));
        cookieSettingButton.setToolTipText("点击设置浏览器Cookie以下载付费/粉丝可见文章");
        actionPanel.add(downloadButton);
        actionPanel.add(stopButton);
        actionPanel.add(Box.createHorizontalStrut(20));
        actionPanel.add(clearButton);
        actionPanel.add(loadFromFileButton);
        actionPanel.add(cookieSettingButton);
        actionPanel.add(autoPdfCheckBox);
        topPanel.add(scrollPane, BorderLayout.CENTER);
        topPanel.add(actionPanel, BorderLayout.SOUTH);
        // 2. 中间面板 (TabbedPane)
        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("微软雅黑", Font.BOLD, DEFAULT_FONT_SIZE));
        tabbedPane.setBorder(new EmptyBorder(5, 5, 0, 5));
        // Tab 1: 待下载任务
        JPanel pendingPanel = createTablePanel("pending");
        tabbedPane.addTab(" 正在下载 / 待处理 ", null, pendingPanel, "查看当前任务队列");
        // Tab 2: 已完成记录
        JPanel completedPanel = createTablePanel("completed");
        tabbedPane.addTab(" 下载完成历史 ", null, completedPanel, "查看下载成功的历史记录");
        // 3. 底部面板 (状态栏)
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(new EmptyBorder(5, 10, 5, 10));
        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(100, 20));
        JPanel statusInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        countLabel = new JLabel("总计: 0 | 成功: 0 | 失败: 0");
        statusLabel = new JLabel("就绪");
        pathLabel = new JLabel("保存路径: " + savePath);
        pathLabel.setForeground(Color.GRAY);
        statusInfoPanel.add(countLabel);
        statusInfoPanel.add(new JSeparator(SwingConstants.VERTICAL));
        statusInfoPanel.add(statusLabel);
        statusInfoPanel.add(new JSeparator(SwingConstants.VERTICAL));
        statusInfoPanel.add(pathLabel);
        bottomPanel.add(progressBar, BorderLayout.NORTH);
        bottomPanel.add(statusInfoPanel, BorderLayout.CENTER);
        // 组装主界面
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        add(mainPanel);
        setupEventListeners();
    }

    private JButton createStyledButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.BOLD, DEFAULT_FONT_SIZE));
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFocusPainted(false);
        btn.setPreferredSize(new Dimension(100, 32));
        return btn;
    }

    private JPanel createTablePanel(String type) {
        JPanel panel = new JPanel(new BorderLayout());
        DefaultTableModel model;
        JTable table;
        if ("pending".equals(type)) {
            String[] cols = {"序号", "文章链接", "当前状态", "进度", "详细信息"};
            pendingModel = new DefaultTableModel(cols, 0) {
                @Override
                public boolean isCellEditable(int row, int col) {
                    return false;
                }
            };
            model = pendingModel;
            table = new JTable(model);
            table.getColumnModel().getColumn(0).setMaxWidth(60);
            table.getColumnModel().getColumn(3).setMaxWidth(80);
            pendingTable = table;
        } else {
            String[] cols = {"序号", "文章标题", "下载结果", "文件大小", "耗时", "本地路径"};
            completedModel = new DefaultTableModel(cols, 0) {
                @Override
                public boolean isCellEditable(int row, int col) {
                    return false;
                }
            };
            model = completedModel;
            table = new JTable(model);
            table.getColumnModel().getColumn(0).setMaxWidth(60);
            table.getColumnModel().getColumn(2).setMaxWidth(100);
            table.getColumnModel().getColumn(3).setMaxWidth(100);
            table.getColumnModel().getColumn(4).setMaxWidth(100);
            completedTable = table;
        }
        table.setRowHeight(30);
        table.setFont(new Font("微软雅黑", Font.PLAIN, DEFAULT_FONT_SIZE));
        table.getTableHeader().setFont(new Font("微软雅黑", Font.BOLD, DEFAULT_FONT_SIZE));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(230, 230, 230));
        // 居中渲染
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        table.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
        JScrollPane sp = new JScrollPane(table);
        sp.getViewport().setBackground(Color.WHITE);
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    private void initData() {
        File saveDir = new File(savePath);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        executorService = Executors.newFixedThreadPool(maxConcurrentDownloads);
    }

    private void setupEventListeners() {
        downloadButton.addActionListener(e -> startDownload());
        stopButton.addActionListener(e -> stopDownload());
        clearButton.addActionListener(e -> clearInput());
        loadFromFileButton.addActionListener(e -> importUrlsFromFile());
        cookieSettingButton.addActionListener(e -> setupCookie());
    }

    private void startDownload() {
        if (isDownloading) {
            return;
        }
        String inputText = urlTextArea.getText().trim();
        if (inputText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入文章访问地址");
            return;
        }
        // 初始化
        pendingModel.setRowCount(0);
        completedModel.setRowCount(0);
        pendingUrls.clear();
        String[] lines = inputText.split("\n");
        int idx = 1;
        for (String line : lines) {
            line = line.trim();
            if (isValidCsdnUrl(line)) {
                pendingUrls.add(line);
                pendingModel.addRow(new Object[]{idx++, line, "等待中", "0%", ""});
            }
        }
        if (pendingUrls.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有有效的CSDN链接");
            return;
        }
        // 切换 UI 状态
        isDownloading = true;
        downloadButton.setEnabled(false);
        stopButton.setEnabled(true);
        tabbedPane.setSelectedIndex(0);
        // 调用下载处理方法
        executeBatchDownload();
    }

    /**
     * 批量下载任务调度逻辑
     **/
    private void executeBatchDownload() {
        int total = pendingUrls.size();
        progressBar.setMaximum(total);
        progressBar.setValue(0);
        // 初始化完成计数器
        completedCount.set(0);
        // 创建单例 Downloader (避免循环内 new)
        CSDNDownloader downloader = new CSDNDownloader();
        // 遍历任务，提交到线程池
        for (int i = 0; i < total; i++) {
            final int index = i;
            final String url = pendingUrls.get(i);
            // 提交任务到 ExecutorService (实现真正的并发)
            executorService.submit(() -> {
                if (!isDownloading) {
                    // 响应停止按钮
                    return;
                }
                // 1. 更新状态：开始下载
                SwingUtilities.invokeLater(() -> {
                    updatePendingTable(index, "下载中", "30%", "正在获取内容...");
                });
                //使用 downloadStatusMap 记录当前 URL 正在处理
                downloadStatusMap.put(url, "Downloading");
                // 2. 执行下载 (耗时 IO)
                DownloadResult result = downloader.downloadArticle(url);
                // 将结果存入 completedDownloads 列表（用于后续导出等功能）
                completedDownloads.add(result);
                // 3. 处理结果
                if (result.isSuccess()) {
                    downloadStatusMap.put(url, "Success");
                    SwingUtilities.invokeLater(() -> updatePendingTable(index, "处理中", "80%", "生成文件..."));
                    // 保存文件 (包含PDF 生成)
                    saveHtmlFile(result, index + 1);
                } else {
                    downloadStatusMap.put(url, result.getHttpStatus() == 404 ? "NotFound" : "Failed");
                }
                // 4. 延时 (遵守 config.properties 的 delay.ms，防止封 IP)
                // 注意：并发环境下，这个延时是针对每个线程的，不是全局串行
                try {
                    Thread.sleep(delayBetweenDownloads);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 5. 更新最终 UI
                SwingUtilities.invokeLater(() -> {
                    updatePendingTable(index, result.isSuccess() ? "完成" : "失败", "100%", result.isSuccess() ? "成功" : result.getError());
                    updateCompletedTable(result, index + 1);
                    int current = completedCount.incrementAndGet();
                    progressBar.setValue(current);
                    statusLabel.setText(String.format("进度: %d / %d", current, total));
                    // 检查是否全部完成
                    if (current == total) {
                        finishDownload();
                    }
                });
            });
        }
    }

    private void finishDownload() {
        isDownloading = false;
        downloadButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusLabel.setText("任务已完成");
        JOptionPane.showMessageDialog(this, "批量下载任务已完成！");
        // 自动跳到“已下载”标签页查看结果
        tabbedPane.setSelectedIndex(1);
    }

    private void updatePendingTable(int row, String status, String progress, String msg) {
        SwingUtilities.invokeLater(() -> {
            if (row < pendingModel.getRowCount()) {
                pendingModel.setValueAt(status, row, 2);
                pendingModel.setValueAt(progress, row, 3);
                pendingModel.setValueAt(msg, row, 4);
            }
        });
    }

    private void updateCompletedTable(DownloadResult result, int index) {
        String status = result.isSuccess() ? "成功" : "失败";
        String size = result.getContentLength() > 0 ? String.format("%.1f KB", result.getContentLength() / 1024.0) : "0";
        String path = result.isSuccess() ? savePath : "-";
        // 如果有特殊标记
        if (result.getTitle() != null && result.getTitle().startsWith("[需关注]")) {
            status = "限制内容(已尝试破解)";
        }
        completedModel.addRow(new Object[]{index, result.getTitle(), status, size, result.getDownloadTime() + "ms", path});
        // 如果成功，保存文件
        if (result.isSuccess()) {
            saveHtmlFile(result, index);
        }
    }

    private void saveHtmlFile(DownloadResult result, int index) {
        try {
            File baseDir = new File(savePath);
            if (!baseDir.exists()) {
                baseDir.mkdirs();
            }
            String safeTitle = result.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
            String fileName = String.format("%03d_%s", index, safeTitle);
            // 1. 保存 HTML
            File htmlFile = new File(baseDir, fileName + ".html");
            org.apache.commons.io.FileUtils.writeStringToFile(htmlFile, result.getHtml(), "UTF-8");
            // 2. 如果勾选了“同时生成PDF”
            if (autoPdfCheckBox.isSelected()) {
                File pdfFile = new File(baseDir, fileName + ".pdf");
                try {
                    com.ctgu.util.PdfGenerator.generate(result.getHtml(), pdfFile.getAbsolutePath());
                } catch (Exception ex) {
                    log.error("PDF生成失败: " + safeTitle, ex);
                }
            }
        } catch (IOException e) {
            log.error("文件保存失败", e);
        }
    }

    private void stopDownload() {
        isDownloading = false;
        for (Future<?> f : futures) f.cancel(true);
        statusLabel.setText("已停止");
        downloadButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void clearInput() {
        urlTextArea.setText("");
        pendingModel.setRowCount(0);
        completedModel.setRowCount(0);
        progressBar.setValue(0);
    }

    // Cookie设置对话框
    private void setupCookie() {
        JDialog dialog = new JDialog(this, "设置Cookie", true);
        dialog.setSize(500, 300);
        dialog.setLocationRelativeTo(this);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea cookieArea = new JTextArea();
        cookieArea.setLineWrap(true);
        cookieArea.setBorder(BorderFactory.createTitledBorder("请粘贴CSDN的Cookie字符串"));
        // 尝试读取现有Cookie
        File cookieFile = new File("cookie.txt");
        if (cookieFile.exists()) {
            try {
                cookieArea.setText(FileUtils.readFileToString(cookieFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
            }
        }
        JButton saveBtn = new JButton("保存配置");
        saveBtn.addActionListener(e -> {
            String c = cookieArea.getText().trim();
            if (!c.isEmpty()) {
                try {
                    FileUtils.writeStringToFile(cookieFile, c, StandardCharsets.UTF_8);
                    JOptionPane.showMessageDialog(dialog, "Cookie保存成功！下次下载将生效。");
                    dialog.dispose();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        });
        JLabel tip = new JLabel("<html><body>提示：登录CSDN后按F12 -> Network -> 刷新页面 -><br>找到任意请求 -> Request Headers -> 复制Cookie值</body></html>");
        panel.add(new JScrollPane(cookieArea), BorderLayout.CENTER);
        panel.add(tip, BorderLayout.NORTH);
        panel.add(saveBtn, BorderLayout.SOUTH);
        dialog.add(panel);
        dialog.setVisible(true);
    }

    private void setupMenu() {
        JMenuBar menuBar = new JMenuBar();
        // 文件菜单
        JMenu fileMenu = new JMenu("文件");
        fileMenu.setFont(new Font("微软雅黑", Font.PLAIN, DEFAULT_FONT_SIZE));
        JMenuItem importItem = new JMenuItem("导入URL列表");
        importItem.addActionListener(e -> importUrlsFromFile());
        JMenuItem exportItem = new JMenuItem("导出下载记录");
        exportItem.addActionListener(e -> exportDownloadRecords());
        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.addActionListener(e -> {
            stopDownload();
            System.exit(0);
        });
        fileMenu.add(importItem);
        fileMenu.add(exportItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        // 设置菜单
        JMenu settingsMenu = new JMenu("设置");
        settingsMenu.setFont(new Font("微软雅黑", Font.PLAIN, DEFAULT_FONT_SIZE));
        JMenuItem pathItem = new JMenuItem("设置保存路径");
        pathItem.addActionListener(e -> changeSavePath());
        JMenuItem cookieItem = new JMenuItem("设置Cookie");
        cookieItem.addActionListener(e -> setupCookie());
        JMenuItem threadsItem = new JMenuItem("设置并发数");
        threadsItem.addActionListener(e -> setConcurrentThreads());
        settingsMenu.add(pathItem);
        settingsMenu.add(cookieItem);
        settingsMenu.add(threadsItem);
        // 工具菜单
        JMenu toolsMenu = new JMenu("工具");
        toolsMenu.setFont(new Font("微软雅黑", Font.PLAIN, DEFAULT_FONT_SIZE));
        JMenuItem validateItem = new JMenuItem("验证URL有效性");
        validateItem.addActionListener(e -> validateUrls());
        JMenuItem clearLogsItem = new JMenuItem("清理临时文件");
        clearLogsItem.addActionListener(e -> clearTempFiles());
        toolsMenu.add(validateItem);
        toolsMenu.add(clearLogsItem);
        // 帮助菜单
        JMenu helpMenu = new JMenu("帮助");
        helpMenu.setFont(new Font("微软雅黑", Font.PLAIN, DEFAULT_FONT_SIZE));
        JMenuItem helpItem = new JMenuItem("使用帮助");
        helpItem.addActionListener(e -> showHelpDialog());
        JMenuItem aboutItem = new JMenuItem("关于");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(helpItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);
        menuBar.add(fileMenu);
        menuBar.add(settingsMenu);
        menuBar.add(toolsMenu);
        menuBar.add(helpMenu);
        setJMenuBar(menuBar);
    }

    private void changeSavePath() {
        JFileChooser fileChooser = new JFileChooser(savePath);
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("选择保存目录");
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            savePath = fileChooser.getSelectedFile().getAbsolutePath();
            pathLabel.setText("保存路径: " + savePath);
            // 保存配置
            saveConfig();
            JOptionPane.showMessageDialog(this, "保存路径已更新为: " + savePath, "设置成功", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    //url校验
    private void validateUrls() {
        String text = urlTextArea.getText();
        if (text == null || text.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "输入框为空，请输入URL。", "验证结果", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String[] lines = text.split("\n");
        int validCount = 0;
        int invalidCount = 0;
        StringBuilder invalidUrls = new StringBuilder();
        for (String line : lines) {
            String url = line.trim();
            if (url.isEmpty()) continue;
            if (isValidCsdnUrl(url)) {
                validCount++;
            } else {
                invalidCount++;
                invalidUrls.append(url).append("\n");
            }
        }
        String message = String.format("验证完成：\n有效链接: %d 个\n无效链接: %d 个", validCount, invalidCount);
        if (invalidCount > 0) {
            message += "\n\n无效链接示例:\n" + (invalidUrls.length() > 100 ? invalidUrls.substring(0, 100) + "..." : invalidUrls.toString());
        }
        JOptionPane.showMessageDialog(this, message, "URL 有效性验证", invalidCount > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    // 清理临时文件的逻辑
    private void clearTempFiles() {
        File baseDir = new File(savePath, "CSDN_Downloads");
        if (!baseDir.exists() || baseDir.listFiles() == null) {
            JOptionPane.showMessageDialog(this, "下载目录不存在或为空。", "清理结果", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this, "确定要清理下载目录 (" + baseDir.getAbsolutePath() + ") 下的所有文件吗？\n此操作不可恢复！", "清理确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            try {
                org.apache.commons.io.FileUtils.cleanDirectory(baseDir);
                JOptionPane.showMessageDialog(this, "清理完成！", "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                log.error("清理文件失败", e);
                JOptionPane.showMessageDialog(this, "清理失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 加载配置信息
     * 优先从 src/main/resources/config.properties 读取内置默认配置
     **/
    private void loadConfig() {
        Properties props = new Properties();
        // 1. 使用 getResourceAsStream 从类路径加载资源文件
        try (InputStream is = getClass().getResourceAsStream("/" + configFileName)) {
            if (is != null) {
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                log.info("成功加载内置配置文件: {}", configFileName);
            } else {
                log.error("在 resources 下未找到配置文件: {}", configFileName);
                // 如果 resources 下也没有，尝试读取当前运行目录下的物理文件
                File externalFile = new File(configFileName);
                if (externalFile.exists()) {
                    try (FileInputStream fis = new FileInputStream(externalFile)) {
                        props.load(fis);
                    }
                }
            }
            // 2. 解析配置属性
            // 读取保存路径 output.dir
            String dir = props.getProperty("output.dir");
            if (dir != null && !dir.isEmpty()) {
                this.savePath = dir;
            }
            // 读取线程池大小 thread.pool.size
            String threadSize = props.getProperty("thread.pool.size");
            if (threadSize != null) {
                try {
                    this.maxConcurrentDownloads = Integer.parseInt(threadSize.trim());
                } catch (NumberFormatException e) {
                    log.warn("并发数格式错误，使用默认值: {}", maxConcurrentDownloads);
                }
            }
            // 读取下载间隔 delay.ms
            String delay = props.getProperty("delay.ms");
            if (delay != null) {
                try {
                    this.delayBetweenDownloads = Long.parseLong(delay.trim());
                } catch (NumberFormatException e) {
                    log.warn("延迟时间格式错误，使用默认值: {}", delayBetweenDownloads);
                }
            }
            // 3. 更新 UI 显示
            if (pathLabel != null) {
                pathLabel.setText("保存路径: " + savePath);
            }
            // 4. 初始化线程池
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdownNow();
            }
            executorService = Executors.newFixedThreadPool(maxConcurrentDownloads);
        } catch (IOException e) {
            log.error("加载配置时发生 IO 异常", e);
        }
    }

    /**
     * 配置文件持久化：保存当前路径与并发设置
     **/
    private void saveConfig() {
        Properties props = new Properties();
        props.setProperty("thread.pool.size", String.valueOf(maxConcurrentDownloads));
        props.setProperty("timeout.seconds", "10");
        props.setProperty("retry.count", "3");
        props.setProperty("delay.ms", String.valueOf(delayBetweenDownloads));
        props.setProperty("output.dir", savePath);
        try (FileOutputStream fos = new FileOutputStream(configFileName)) {
            props.store(fos, "CSDN Downloader Configuration");
            log.info("配置已保存至 {}", configFileName);
        } catch (IOException e) {
            log.error("保存配置失败", e);
        }
    }

    // 导出下载记录
    private void exportDownloadRecords() {
        //        if (completedDownloads.isEmpty()) {
        //            JOptionPane.showMessageDialog(this, "当前没有下载成功的记录可供导出。");
        //            return;
        //        }
        if (completedModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "没有可导出的下载记录。", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("导出下载记录");
        fileChooser.setSelectedFile(new File("download_records_" + System.currentTimeMillis() + ".csv"));
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                // 写入表头 (BOM用于Excel正确打开UTF-8)
                writer.write('\ufeff');
                writer.println("序号,文章标题,下载结果,文件大小,耗时,本地路径");
                // 写入数据
                for (int i = 0; i < completedModel.getRowCount(); i++) {
                    StringBuilder line = new StringBuilder();
                    for (int j = 0; j < completedModel.getColumnCount(); j++) {
                        Object value = completedModel.getValueAt(i, j);
                        String valStr = value == null ? "" : value.toString();
                        // 处理CSV转义：如果包含逗号，用双引号包围
                        if (valStr.contains(",")) {
                            valStr = "\"" + valStr.replace("\"", "\"\"") + "\"";
                        }
                        line.append(valStr);
                        if (j < completedModel.getColumnCount() - 1) line.append(",");
                    }
                    writer.println(line);
                }
                JOptionPane.showMessageDialog(this, "导出成功！\n保存位置: " + file.getAbsolutePath());
            } catch (IOException e) {
                log.error("导出失败", e);
                JOptionPane.showMessageDialog(this, "导出失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 显示使用帮助对话框
     * 从类路径资源文件 (src/main/resources/help.txt) 中动态加载帮助文本
     * 如果文件缺失，将显示预设的错误提示
     **/
    private void showHelpDialog() {
        JTextArea helpArea = new JTextArea(20, 60);
        helpArea.setFont(new Font("微软雅黑", Font.PLAIN, DEFAULT_FONT_SIZE));
        helpArea.setEditable(false);
        helpArea.setLineWrap(true);
        helpArea.setWrapStyleWord(true);
        StringBuilder helpText = new StringBuilder();
        // 使用类加载器读取 resources 下的文件
        try (InputStream is = getClass().getResourceAsStream("/help.txt")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 替换版本号占位符（如果在 help.txt 中使用了 {{version}}）
                        line = line.replace("{{version}}", softVersion);
                        helpText.append(line).append("\n");
                    }
                }
            } else {
                helpText.append("错误：未找到帮助文件 (help.txt)。\n请确保文件位于 resources 目录下。");
                log.error("未找到资源文件: /help.txt");
            }
        } catch (IOException e) {
            helpText.append("无法读取帮助文件内容：").append(e.getMessage());
            log.error("读取帮助文件异常", e);
        }
        helpArea.setText(helpText.toString());
        // 滚动条回到顶部
        helpArea.setCaretPosition(0);
        JScrollPane scrollPane = new JScrollPane(helpArea);
        scrollPane.setPreferredSize(new Dimension(650, 450));
        scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JOptionPane.showMessageDialog(this, scrollPane, "使用帮助", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * 显示“关于”对话框
     * 内容动态加载自资源文件 (src/main/resources/about.txt)
     * 支持占位符 {{version}} 动态替换
     **/
    private void showAboutDialog() {
        StringBuilder aboutText = new StringBuilder();
        // 从 resources 目录读取 about.txt
        try (InputStream is = getClass().getResourceAsStream("/about.txt")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 动态替换版本号
                        line = line.replace("{{version}}", softVersion);
                        aboutText.append(line).append("\n");
                    }
                }
            } else {
                // 如果文件丢失，则显示基础信息
                aboutText.append("CSDN博客下载器 ").append(softVersion).append("\n\n文件 about.txt 丢失。");
            }
        } catch (IOException e) {
            log.error("读取关于文件失败", e);
            aboutText.append("无法加载关于信息。");
        }
        // 使用系统默认的消息对话框显示内容
        JOptionPane.showMessageDialog(this,
                aboutText.toString(),
                "关于",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void setConcurrentThreads() {
        String input = JOptionPane.showInputDialog(this, "请输入并发下载数（1-6）:", String.valueOf(maxConcurrentDownloads));
        if (input != null && !input.trim().isEmpty()) {
            try {
                int threads = Integer.parseInt(input.trim());
                if (threads >= 1 && threads <= 6) {
                    maxConcurrentDownloads = threads;
                    // 重新创建线程池
                    if (!executorService.isShutdown()) {
                        executorService.shutdownNow();
                    }
                    executorService = Executors.newFixedThreadPool(maxConcurrentDownloads);
                    saveConfig();
                    JOptionPane.showMessageDialog(this, "并发数已设置为: " + threads, "设置成功", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this, "请输入1-6之间的数字", "输入错误", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "请输入有效的数字", "输入错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void importUrlsFromFile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String content = FileUtils.readFileToString(fc.getSelectedFile(), StandardCharsets.UTF_8);
                urlTextArea.setText(content);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "读取失败");
            }
        }
    }

    private boolean isValidCsdnUrl(String url) {
        return url != null && url.contains("csdn.net") && url.contains("/article/details/");
    }
}