package com.jqorz.pcmonitor4dlg

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.jqorz.pcmonitor4dlg.model.ExitAction
import com.jqorz.pcmonitor4dlg.tray.TrayManager
import com.jqorz.pcmonitor4dlg.ui.App
import com.jqorz.pcmonitor4dlg.viewmodel.MainViewModelImpl
import com.jqorz.pcmonitor4dlg.viewmodel.createMainViewModel
import org.jetbrains.skia.Image

fun main() {
    val viewModel = createMainViewModel() as MainViewModelImpl
    val settings = viewModel.settings.value

    var isWindowVisible by mutableStateOf(!settings.minimizeOnStartup)
    var shouldExit by mutableStateOf(false)
    var awtWindow by mutableStateOf<java.awt.Window?>(null)

    // 系统托盘
    val trayManager = TrayManager(
        onShowWindow = {
            isWindowVisible = true
            awtWindow?.toFront()
        },
        onExit = {
            viewModel.performExit()
            shouldExit = true
        }
    )

    application {
        // 设置系统托盘
        LaunchedEffect(Unit) {
            trayManager.setup()
        }

        // 托盘菜单退出时，清理并退出应用
        LaunchedEffect(shouldExit) {
            if (shouldExit) {
                trayManager.dispose()
                exitApplication()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                trayManager.dispose()
            }
        }

        val windowState = remember {
            WindowState(
                position = WindowPosition(Alignment.Center),
                size = DpSize(480.dp, 720.dp)
            )
        }

        // 将 AWT Image 转为 Compose Painter，保证窗口图标与托盘图标一致
        val appIcon = remember {
            val awtImage = TrayManager.createAppIcon()
            val buffered = awtImage as java.awt.image.BufferedImage
            val baos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(buffered, "png", baos as java.io.OutputStream)
            val bytes = baos.toByteArray()
            BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }

        Window(
            onCloseRequest = {
                val action = viewModel.settings.value.exitAction
                when (action) {
                    ExitAction.ASK -> viewModel.setShowExitDialog(true)
                    ExitAction.MINIMIZE -> isWindowVisible = false
                    ExitAction.EXIT -> {
                        viewModel.performExit()
                        exitApplication()
                    }
                }
            },
            title = "PCMonitor4DLG",
            state = windowState,
            visible = isWindowVisible,
            icon = appIcon
        ) {
            // 捕获 AWT 窗口引用，用于托盘点击时拉到前台
            awtWindow = this.window

            App(
                viewModel = viewModel,
                onMinimizeToTray = { isWindowVisible = false },
                onExit = {
                    trayManager.dispose()
                    exitApplication()
                }
            )
        }
    }
}
