# NativeChat Android

原生 Android 聊天客户端，基于 DeepSeek chat-demo 的协议与设计思路实现，不使用 WebView。

包含流式对话、附件、历史导入导出、会话搜索与上下文管理。项目仍在开发，使用前请自行备份数据；上下文压缩可能丢失信息。

## 构建

`build.ps1` 是原开发环境的 Windows 构建脚本，依赖 Android SDK、JDK、Python 和 D8/R8；其中的工具路径需要按本机环境调整。仓库不包含签名密钥，构建前须自行创建并配置 keystore。使用不同签名的 APK 无法直接覆盖安装原版。

## 许可

本项目采用 GPL-3.0-only，详见 LICENSE。项目参考及移植的 DeepSeek chat-demo 代码依照其 MIT 许可保留版权与许可声明，见 NOTICE-DeepSeek-MIT.txt。
