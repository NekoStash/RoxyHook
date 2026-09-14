# RoxyHook

[![JitPack](https://jitpack.io/v/NekoStash/RoxyHook.svg)](https://jitpack.io/#NekoStash/RoxyHook)

RoxyHook 是面向 **LibXposed API 102** 的 Kotlin 封装与开发工具链。它提供平台无关的 Hook 核心、Android 生命周期与通信能力、LibXposed 平台适配、KSP 入口生成器，以及用于模块工程的 Gradle 插件。

> 本项目只面向 LibXposed API 102 及更高版本；不实现传统 XposedBridge、zygote 注入或 XResources 兼容层。

## 模块

| 模块 | 用途 |
| --- | --- |
| `roxy-annotations` | `@RoxyEntry` 模块入口注解。 |
| `roxy-core` | Hook DSL、运行时、作用域、偏好与平台抽象。 |
| `roxy-android` | Android 生命周期、模块资源与认证数据通道。 |
| `roxy-platforms:libxposed` | LibXposed API 102 平台适配、Service 与热重载接入。 |
| `roxy-ksp` | KSP 符号处理器；生成 Xposed 入口和元数据。 |
| `roxy-gradle-plugin` | 模块工程插件：自动依赖、元数据、R8 keep rules 和 APK 校验。 |
| `roxy-testing` | 平台无关 Hook 行为的契约与回归测试。 |
| `samples:demo-module` | 可编译的 LibXposed 模块 APK 示例。 |
| `samples:demo-target` | 被 Hook 的目标应用示例。 |

## 环境要求

- JDK 21
- Android SDK Platform 37
- Android `minSdk` 26
- 可访问 Google Maven、Maven Central 和 Gradle Plugin Portal 的网络

仓库携带标准 Gradle Wrapper（Gradle 9.5.0）：

```bash
./gradlew --version
./gradlew projects
```

Windows 下可使用：

```bat
gradlew.bat --version
gradlew.bat projects
```

## 构建与验证

### 纯 JVM 模块与行为测试

```bash
./gradlew -PjvmOnly=true :roxy-testing:check :roxy-ksp:build :roxy-annotations:build :roxy-core:build
```

`roxy-testing` 会执行 Hook 契约与回归测试；它们是直接运行的测试套件，不依赖 JUnit 测试发现。

### 构建示例 APK

```bash
./gradlew \
  :samples:demo-target:assembleDebug \
  :samples:demo-module:assembleDebug \
  :samples:demo-module:assembleRelease
```

产物默认位于：

```text
samples/demo-target/build/outputs/apk/debug/demo-target-debug.apk
samples/demo-module/build/outputs/apk/debug/demo-module-debug.apk
samples/demo-module/build/outputs/apk/release/demo-module-release-unsigned.apk
```

### 校验 RoxyHook 元数据与 DEX 入口

从仓库根目录运行：

```bash
./gradlew :samples:demo-module:roxyVerifyApk \
  --apk=samples/demo-module/build/outputs/apk/debug/demo-module-debug.apk

./gradlew :samples:demo-module:roxyVerifyApk \
  --apk=samples/demo-module/build/outputs/apk/release/demo-module-release-unsigned.apk
```

该任务验证 APK 中的 LibXposed 元数据和生成入口类是否存在于 DEX；它**不替代** APK 签名检查、框架加载、设备注入或真实 Hook 行为验证。

### 完整源码审计

```bash
python3 tools/audit-source.py
./gradlew build
```

## 在模块工程中使用

源码仓库内的示例通过 `includeBuild("roxy-gradle-plugin")` 使用插件。对于独立工程，应在 `settings.gradle.kts` 同时配置插件与依赖仓库：

```kotlin
pluginManagement {
    repositories {
        maven("https://jitpack.io")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }
}
```

然后在模块的 `build.gradle.kts` 中应用插件：

```kotlin
plugins {
    id("com.android.application")
    id("hk.uwu.roxyhook") version "<tag-or-commit>"
}

roxy {
    scope.add("com.example.target")
    staticScope.set(false)
}
```

插件会为 Android application 模块接入 RoxyHook 平台、KSP 处理器、LibXposed API 和每个变体的元数据。入口使用 `@RoxyEntry` 标注继承 `RoxyModule` 的顶层类型。

### 日志

`roxy-core` 提供 `hk.uwu.roxyhook.RLog` 门面，模块代码可在任意位置直接调用，无需持有运行时或平台引用：

```kotlin
RLog.info("module loaded")
RLog.error("hook failed", throwable)
```

路由规则：每次调用从当前打开的注入运行时（`platform.info.isInjected`）取得平台 logger，因此注入场景下消息进入框架日志；无活动注入运行时（模块自身进程、运行时已关闭、纯 JVM 测试）时回退到 `RoxyLogger.STDERR`，不会静默丢弃。`PackageScope.log` 走同一派发路径并自动附加 `[包名/进程名]` 前缀。RLog 仅弱引用运行时快照，不会产生全局强引用。

### PackageScope 常用上下文

`PackageScope` 统一暴露当次注入事件的常用上下文，减少每个 Hook 里重复保管的样板代码：

| API | 语义 / 前置条件 |
| --- | --- |
| `mainProcessName` / `isMainProcess` / `processName` | 包声明的主进程名与当前进程名。 |
| `appInfo` | 当次 package 事件的 `ApplicationInfo` 防御性快照；system_server 或无平台数据时为 `null`。 |
| `application` / `appContext` / `appResources` | 宿主 Application attach 之后可用；attach 前为 `null`，system_server 中读取会失败。 |
| `systemContext` | **仅 system_server**。经 `SystemContextResolver` 调用 `ActivityThread` 隐藏 API；失败抛出带原因的 `IllegalStateException`，无回退。 |
| `moduleAppFile` | 模块自身 APK `File`（LibXposed `moduleApplicationInfo.sourceDir`）；平台未提供时失败。 |
| `moduleResources` | 模块资源；app 进程用 `appContext`、system_server 用 `systemContext` 作宿主 Context，未就绪即失败。 |
| `dataChannel` | 认证数据通道；自动取 `appContext`，校验包名一致且非 system_server，receiver 由 runtime 管理。 |
| `prefs` / `prefs()` | 默认远程偏好组 `"default"`；`prefs(group)` 指定命名组。需要框架 `REMOTE_PREFERENCES` 能力。 |

`systemContext` 依赖 `ActivityThread.currentActivityThread()`/`getSystemContext()` 隐藏 API（与 YukiHookAPI 相同机制）。该路径受 Android 隐藏 API 限制影响，属于框架授予的受控例外，仅限 system_server；解析失败会显式抛出并携带底层原因，不会静默回退到其它 Context。

## 发布

### 本地 Maven 验证

```bash
./gradlew publishAllToMavenLocal
./gradlew -p roxy-gradle-plugin publishToMavenLocal
```

第一条命令发布所有 JVM JAR 与 Android AAR；第二条命令发布 Gradle 插件及其 plugin marker。

### JitPack

仓库根目录的 [jitpack.yml](jitpack.yml) 使用 JDK 21，并会发布库与 Gradle 插件。推送到 GitHub 后，为提交或 tag 在 JitPack 页面触发构建：

```text
https://jitpack.io/#NekoStash/RoxyHook
```

JitPack 产物坐标遵循：

```text
com.github.NekoStash.RoxyHook:<artifact>:<tag-or-commit>
```

JitPack 构建的实际可用性应以该远程构建日志为准。

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。第三方依赖、Gradle Wrapper 和工具组件受各自许可证约束；请在分发前审阅相关上游许可证与归属信息。
