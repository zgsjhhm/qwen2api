# Qwen2API

一个运行在 Android 上的 Qwen OpenAI 兼容 API 网关。应用在本机启动 HTTP 服务，将 OpenAI 风格的请求转发到 Qwen 网页端接口，并提供 Token/Cookie 配置、系统提示词、工具调用、文件与图片能力。

## 当前版本

- Android applicationId: `com.qwen2api.tx`
- versionName: `1.2.0`
- minSdk: 26
- targetSdk: 34
- ABI: `arm64-v8a`
- APK: [`dist/qwen2api-t2i.apk`](dist/qwen2api-t2i.apk)

APK SHA-256:

```text
2c7a644c3d1694c8265a3c52eb83a091fe86d3598943d743339dcd5180142586
```

## 构建

使用 Android Studio 或已安装的 Gradle 环境打开本目录：

```bash
./gradlew assembleDebug
```

若没有 Gradle wrapper，可使用与项目兼容的 Gradle 8.x 执行：

```bash
gradle assembleDebug
```

输出位于 `app/build/outputs/apk/debug/`。发布签名配置通过本地未入库的 `keystore.properties` 提供，示例配置不会提交到仓库。

## 使用

1. 安装 APK。
2. 在应用内配置 Qwen JWT 或 Cookie。
3. 启动网关服务。
4. 在客户端使用应用显示的本地 OpenAI 兼容地址、API Key 和模型。

具体的请求格式、文件上传、多模态、工具调用和故障排查说明见 [`reference/README.md`](reference/README.md)。

## 目录

- `app/src/main/`：Android 应用源码
- `app/src/test/`：单元测试
- `reference/`：配套 Qwen Studio API 参考实现与说明
- `tools/`：验证和端到端辅助脚本
- `dist/`：当前发布 APK

## 免责声明

本项目仅供个人学习研究和本地自动化使用。请遵守 Qwen 及相关上游服务的条款，不要将账号凭证提交到仓库，也不要用于高并发或未经授权的服务转售。
