# Qwen2API

一个运行在 Android 上的 Qwen OpenAI 兼容 API 网关。应用在本机启动 HTTP 服务，将 OpenAI 风格的请求转发到 Qwen 网页端接口，并提供 Token/Cookie 配置、系统提示词、工具调用、文件与图片能力。

## 当前版本

- Android applicationId: `com.qwen2api.tx`
- versionName: `1.4.0`
- minSdk: 26
- targetSdk: 34
- ABI: `arm64-v8a`

APK 不入库，通过下方 Release 构建流程在本地产出。

## 构建

使用 Android Studio 或已安装的 Gradle 环境打开本目录：

```bash
./gradlew assembleRelease
```

若没有 Gradle wrapper，可使用与项目兼容的 Gradle 8.x 执行：

```bash
gradle assembleRelease
```

签名的 Release APK 输出位于 `app/build/outputs/apk/release/`。发布签名配置通过本地未入库的 `keystore.properties` 提供（`storeFile` 指向密钥库、`keyAlias`/`storePassword`/`keyPassword`），文件不存在时 Release 签名不会生效。

## 使用

1. 安装 APK。
2. 在应用内配置 Qwen JWT 或 Cookie。
3. 启动网关服务。
4. 在客户端使用应用显示的本地 OpenAI 兼容地址、API Key 和模型。

### 多账号自动切换

「配置」页可添加多个 Qwen 账号，网关按「最久没用过的先用」在它们之间轮转；
某个账号失效（token 过期 / 额度用尽 / 触发风控）时自动换下一个重试，对调用方透明。

- 失败会记在该账号的健康度上并进入冷却（默认 10 分钟，可调），冷却结束自动回到轮转；
- 配置页原有的单个 token 与列表里的账号在路由上完全等价，不必重复添加（重复凭证会被拒绝）；
- 换号重试次数上限、冷却时长在「接入 → 设置」里调整；关闭多账号即退回单账号行为。

### 调用日志与导出

「日志」页展示每次请求的结果（成功/失败、耗时、落在哪个账号、尝试与切换次数），
失败汇总与最近一次失败置顶。日志同时落盘，App 重启后仍在。

「导出并分享」产出 Markdown 文本（含失败汇总、账号链路与每轮诊断行），
可直接经系统分享发给他人或 AI 用于定位问题；也可以只复制全文到剪贴板。

具体的请求格式、文件上传、多模态、工具调用和故障排查说明见 [`reference/README.md`](reference/README.md)。

## 目录

- `app/src/main/`：Android 应用源码
- `app/src/test/`：单元测试
- `reference/`：配套 Qwen Studio API 参考实现与说明
- `tools/`：验证和端到端辅助脚本
- `dist/`：本地构建产物（不入库）

## 免责声明

本项目仅供个人学习研究和本地自动化使用。请遵守 Qwen 及相关上游服务的条款，不要将账号凭证提交到仓库，也不要用于高并发或未经授权的服务转售。
