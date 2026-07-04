package com.jqorz.pcmonitor4dlg.tray

import java.awt.*
import java.awt.event.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

class TrayManager(
    private val onShowWindow: () -> Unit,
    private val onExit: () -> Unit
) {
    private var trayIcon: TrayIcon? = null
    private var isSupported = SystemTray.isSupported()
    private var popupDialog: JFrame? = null

    companion object {
        fun createAppIcon(): Image {
            val size = 32
            val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g.color = Color(0x2E, 0x7D, 0x32)
            g.fill(RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), 8f, 8f))

            g.color = Color(0x1B, 0x5E, 0x20)
            g.fill(RoundRectangle2D.Float(3f, 3f, (size - 6).toFloat(), (size - 6).toFloat(), 5f, 5f))

            g.color = Color(0x69, 0xF0, 0xAE)
            g.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val path = java.awt.geom.GeneralPath()
            path.moveTo(5.0, 17.0); path.lineTo(9.0, 17.0)
            path.lineTo(12.0, 10.0); path.lineTo(16.0, 23.0)
            path.lineTo(20.0, 8.0); path.lineTo(24.0, 20.0)
            path.lineTo(27.0, 17.0)
            g.draw(path)

            g.color = Color(0xA5, 0xD6, 0xA7)
            g.fillOval(7, 25, 3, 3); g.fillOval(14, 25, 3, 3); g.fillOval(21, 25, 3, 3)

            g.dispose()
            return image
        }
    }

    fun setup() {
        if (!isSupported) return

        val tray = SystemTray.getSystemTray()
        val image = createAppIcon()

        trayIcon = TrayIcon(image, "PCMonitor4DLG", null).apply {
            isImageAutoSize = true
            addActionListener { onShowWindow() }

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        showCustomMenu()
                    }
                }
            })
        }

        try {
            tray.add(trayIcon)
        } catch (_: AWTException) {
        }
    }

    private fun showCustomMenu() {
        popupDialog?.dispose()

        val mouseLoc = MouseInfo.getPointerInfo().location
        val font = Font("Microsoft YaHei", Font.PLAIN, 13)
        val hoverColor = Color(0xE8, 0xE8, 0xE8)
        val textColor = Color(0x33, 0x33, 0x33)
        val separatorColor = Color(0xDD, 0xDD, 0xDD)

        val dialog = JFrame()
        dialog.isUndecorated = true
        dialog.isAlwaysOnTop = true
        dialog.background = Color.WHITE
        dialog.focusableWindowState = true
        dialog.type = Window.Type.POPUP

        val panel = JPanel()
        panel.isOpaque = true
        panel.background = Color.WHITE
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)

        fun createMenuItem(text: String, onClick: () -> Unit): JPanel {
            val item = JPanel(BorderLayout())
            item.isOpaque = true
            item.background = Color.WHITE
            item.border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
            item.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            item.maximumSize = Dimension(Int.MAX_VALUE, 36)
            item.preferredSize = Dimension(120, 36)

            val label = JLabel(text)
            label.font = font
            label.foreground = textColor
            item.add(label, BorderLayout.CENTER)

            item.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    item.background = hoverColor
                    item.repaint()
                }
                override fun mouseExited(e: MouseEvent) {
                    item.background = Color.WHITE
                    item.repaint()
                }
                override fun mousePressed(e: MouseEvent) {
                    dialog.dispose()
                    onClick()
                }
            })
            return item
        }

        panel.add(createMenuItem("显示主窗口") { onShowWindow() })

        // 分割线
        val separator = JPanel()
        separator.isOpaque = true
        separator.background = separatorColor
        separator.preferredSize = Dimension(120, 1)
        separator.maximumSize = Dimension(Int.MAX_VALUE, 1)
        panel.add(Box.createVerticalStrut(4))
        panel.add(separator)
        panel.add(Box.createVerticalStrut(4))

        panel.add(createMenuItem("退出") { onExit() })

        dialog.contentPane = panel
        dialog.pack()

        // 右下角对齐鼠标
        dialog.setLocation(mouseLoc.x - dialog.width, mouseLoc.y - dialog.height)

        // 失焦自动关闭
        dialog.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent) {
                dialog.dispose()
            }
        })

        dialog.isVisible = true
        dialog.requestFocus()
        popupDialog = dialog
    }

    fun showNotification(title: String, message: String, type: TrayIcon.MessageType = TrayIcon.MessageType.INFO) {
        trayIcon?.displayMessage(title, message, type)
    }

    fun dispose() {
        popupDialog?.dispose()
        popupDialog = null
        trayIcon?.let { SystemTray.getSystemTray().remove(it) }
        trayIcon = null
    }
}
