# ai-bookkeeper

Android AI 智能记账应用 - Kotlin + Compose + Room + Azure OpenAI

## 📖 文档

| 文档 | 说明 |
|------|------|
| [PRD.md](./PRD.md) | 产品需求文档 — 功能定义、User Stories、验收标准 |
| [架构设计](./docs/architecture/ARCHITECTURE.md) | 系统架构总览 — 模块分层、Hilt DI、数据流、技术选型 |
| [数据库 Schema](./docs/architecture/DATABASE_SCHEMA.md) | Room 数据库设计 — Entity、DAO、索引、迁移策略 |
| [Azure OpenAI 集成](./docs/architecture/AZURE_OPENAI_INTEGRATION.md) | AI 集成方案 — Prompt 设计、离线回退、错误处理 |
| [模块间接口契约](./docs/architecture/MODULE_CONTRACTS.md) | 接口定义 — Repository、Domain Model、导航路由 |

## ⚡ 快速开始

### 前置要求

| 工具 | 最低版本 | 说明 |
|------|---------|------|
| **JDK** | 17 | Gradle 8.7 + AGP 要求 JDK 17+ |
| **Android SDK** | API 34 | compileSdk 34 |
| **Android Studio** | Hedgehog+ | 推荐，内置 JDK 17 |

### 本地安装 JDK（Windows）

```powershell
# 方式 1: 运行项目脚本（需管理员权限）
powershell -ExecutionPolicy Bypass -File scripts/setup-jdk.ps1

# 方式 2: 手动 winget 安装
winget install EclipseAdoptium.Temurin.17.JDK

# 验证
java -version   # 应显示 17.x 或更高
```

### 构建 & 测试

```bash
./gradlew build     # 编译 + lint + 单元测试
./gradlew test      # 仅单元测试
```

### CI

项目已配置 GitHub Actions (`.github/workflows/ci.yml`)，push/PR 到 `main` 时自动执行构建和测试。

## 🛠 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **存储**: Room (离线优先)
- **DI**: Hilt
- **AI**: Azure OpenAI (自然语言提取)
- **OCR**: Google ML Kit
- **架构**: MVVM + Clean Architecture

Created by Agent Hub.
