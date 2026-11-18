# chat-bot

挂在聊天软件旁边的 AI 副驾。读当前对话，判断对方想要什么，给出可一键填入的回复——**发送永远由你手动点**。

## 快速开始

1. 下载 [chat-bot-v1.0.apk](cn/apk/chat-bot-v1.0.apk)，或 `adb install -r cn/apk/chat-bot-v1.0.apk`
2. 打开 App → 设置 → 填「判断接口」的 API Key（其余留空自动继承）
3. 按主页清单开无障碍、悬浮窗、自启动权限
4. 进聊天界面，等分析卡片出来；或切成「一键直填」用推特上的小绿球

详细说明见 **[cn/README.md](cn/README.md)**

## 功能一览

- **先判断再写字**：判断模型先给出对方真实意图、危险程度、该不该回，再起草回复并排序
- **两种填入方式**：点选候选（默认）/ 一键直填（推特专属，点一下直接填，再点换下一条）
- **推特按内容写评论**：读帖子正文 + 已有回复，每条评论都要求「回应内容 + 表达观点」，四种风格可切换
- **在线更新**：查 GitHub Releases，下载后交给系统安装器
- **本地知识库**：联系人与笔记自动带进上下文

## 平台

QQ · X（私信 + 帖子）· 飞书（OCR 兜底）· WhatsApp（一对一）· 其它 App 走手动截屏识别

## 编译

```bash
cd cn
./gradlew assembleDebug
```

需要 JDK 17 + Android SDK 35。见 [cn/docs/BUILD-NOTES.md](cn/docs/BUILD-NOTES.md)。

## 发版

```bash
git tag v1.12 && git push origin v1.12
```

CI 自动构建签名包并发布 Release（需配置 4 个签名 Secrets）。

---

本工程基于开源项目 [jev-chat/jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis) 二次开发，按原项目许可证使用。
