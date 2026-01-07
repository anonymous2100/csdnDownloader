package com.ctgu;

import com.ctgu.ui.CSDNDownloaderUI;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;

/**
 * @author lh2
 * @version 1.0
 * @description:
 * @date 2026-01-06 14:31
 */
public class Main {
    /**
     * 启动方法
     *
     * @param args
     */
    public static void main(String[] args) {
        // 设置抗锯齿和系统风格
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                // 调整表格行高和字体
                UIManager.put("Table.rowHeight", 30);
                UIManager.put("Label.font", new Font("微软雅黑", Font.PLAIN, 16));
                UIManager.put("Button.font", new Font("微软雅黑", Font.PLAIN, 16));
                // 设置按钮显示效果
                UIManager.put("OptionPane.buttonFont", new FontUIResource(new Font("微软雅黑", Font.ITALIC, 16)));
                // 设置文本显示效果
                UIManager.put("OptionPane.messageFont", new FontUIResource(new Font("微软雅黑", Font.ITALIC, 16)));

                CSDNDownloaderUI ui = new CSDNDownloaderUI();
                ui.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
