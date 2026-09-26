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

// 应用版本号单一来源：打包(packageVersion)与运行时"关于"显示都从这里取
val appVersion = "1.0.2"

compose.desktop {
    application {
        mainClass = "com.jqorz.pcmonitor4dlg.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PCMonitor"
            packageVersion = appVersion

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

// 构建时生成 app.properties（含版本号），供运行时"关于"读取；dev 运行和安装版均可用
val generateVersionProperties by tasks.registering {
    val version = appVersion // 捕获为局部值，避免 doLast 持有脚本对象引用（配置缓存要求）
    val outputDir = layout.buildDirectory.dir("generated-resources/appVersion")
    inputs.property("appVersion", version)
    outputs.dir(outputDir)
    doLast {
        val propsFile = outputDir.get().file("app.properties").asFile
        propsFile.parentFile.mkdirs()
        propsFile.writeText("app.version=$version\n")
    }
}
sourceSets.main {
    resources.srcDir(generateVersionProperties.map { it.outputs.files.singleFile })
}
