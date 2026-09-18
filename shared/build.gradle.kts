plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// ble_service.exe（PyInstaller产物，9MB二进制）不进git：打包时由脚本生成到
// build/generated/ble-exe 并作为jvmMain资源挂入，修改py后gradle自动重新生成
val bleExeDir = layout.buildDirectory.dir("generated/ble-exe")

val generateBleExe by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine("cmd", "/c", rootProject.file("tools/build_ble_exe.cmd").absolutePath)
    inputs.file(rootProject.file("tools/build_ble_exe.cmd"))
    inputs.file(rootProject.file("shared/src/jvmMain/resources/ble_service.py"))
    outputs.file(bleExeDir.map { it.file("ble_service.exe") })
}

kotlin {
    jvm()
    
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        jvmMain.dependencies {
            implementation(libs.oshi.core)
            implementation(libs.jna)
            implementation(libs.jna.platform)
            implementation(libs.jLibreHardwareMonitor)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain {
            resources.srcDir(bleExeDir)
        }
    }
}

// 资源处理前确保exe已生成
tasks.matching { it.name == "jvmProcessResources" }.configureEach { dependsOn(generateBleExe) }