import org.gradle.declarative.dsl.schema.FqName.Empty.packageName
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.reload.core.Environment.Companion.application
import java.awt.SystemColor.menu

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.jqorz.pcmonitor4dlg.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PCMonitor"
            packageVersion = "1.0.1"

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menu = true
                shortcut = true
                dirChooser = true
            }
        }
    }
}

// 打包完成后自动拷贝MSI产物到桌面（桌面被重定向到 D:\桌面，与固件flashtool/OTA选文件的解析逻辑一致）
val desktopDir = sequenceOf(
    File(System.getProperty("user.home"), "Desktop"),
    File("D:\\桌面")
).firstOrNull { it.isDirectory } ?: File("D:\\桌面")

val copyPackageToDesktop by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("compose/binaries/main/msi"))
    include("*.msi")
    into(desktopDir)
}

tasks.matching { it.name == "packageMsi" }.configureEach {
    finalizedBy(copyPackageToDesktop)
}
