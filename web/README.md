# AI Bookkeeper Web

React、TypeScript 与 Vite 构建的 AI Bookkeeper Web 客户端。应用采用 Microsoft Entra External ID 强制登录；认证完成前不会请求或展示任何账本数据。

## 本地运行

要求 Node.js 20.19+ 或 22.12+。

```powershell
Copy-Item .env.example .env.local
npm install
npm run dev
```

在 `.env.local` 中设置：

| 变量 | 必需 | 说明 |
| --- | --- | --- |
| `VITE_ENTRA_CLIENT_ID` | 是 | Entra External ID SPA 应用客户端 ID |
| `VITE_ENTRA_AUTHORITY` | 是 | CIAM authority，部署应使用 `https://aibookkeeper.ciamlogin.com/aibookkeeper.onmicrosoft.com/` |
| `VITE_API_SCOPE` | 是 | API 委托权限，例如 `api://dc183072-2c27-4ad0-a6a8-3df3b91de4ad/sync.readwrite` |
| `VITE_API_BASE_URL` | 否 | API 根地址；省略时使用生产同步 API |

在 Entra 应用注册中，将本地地址（通常为 `http://localhost:5173`）和生产站点根地址登记为 **Single-page application** 重定向 URI。API 必须允许站点来源的 CORS，并验证访问令牌的 audience 与 scope。

## 质量检查

```powershell
npm test
npm run lint
npm run build
```

## 部署

构建输出位于 `dist/`。Azure Static Web Apps 可将应用目录设为 `web`、构建命令设为 `npm run build`、输出目录设为 `dist`。生产环境使用已提交的 `.env.production` 公共客户端配置；部署环境也可用同名 `VITE_*` 变量覆盖。所有变量都会被 Vite 编译到客户端中，因此只能包含客户端 ID、authority、scope 和 API 地址，绝不能放入客户端密钥。

`staticwebapp.config.json` 提供 SPA 导航回退和安全响应头。若 API 或 Entra 域名变化，应同步更新 Content Security Policy 的 `connect-src` / `form-action`。生产重定向 URI 必须与站点 origin 完全一致。

## 权限与同步

- `OWNER`：读写交易并管理邀请、成员和角色。
- `EDITOR`：读写交易，不能管理成员。
- `VIEWER`：仅查看；UI 隐藏编辑入口，API 客户端也会在获取令牌或发送请求前拒绝 push。
- 账本带有 `PERSONAL` 或 `FAMILY` 模式。所有者可在设置中转换模式；邀请首位成员时会自动转为 `FAMILY`。
- 转回 `PERSONAL` 会撤销全部成员访问和待处理邀请，但保留所有交易，界面会要求明确确认。
- 每个账本分别保存交易、加载状态与增量 cursor，切换时不会混用数据。
- 所有 `FAMILY` 账本的 `sync/pull` 和 `sync/push` 都发送明确的 `ledgerId`；个人账本继续兼容不带 `ledgerId` 的默认同步端点。

## 账本分类

新增和编辑交易的分类选项来自当前账本的 `/api/categories`，不再使用 Web 固定列表。
新账本的默认名称、图标、颜色和排序与 Android 一致；已有交易中的自定义分类也会保留。
“支出分类 → 管理分类”和记账窗口中的“新增分类”可为当前账本添加分类及自定义 Emoji。

分类属于账本而不是设备。家庭成员共享同一份分类目录，`OWNER` 和 `EDITOR` 可以新增，
`VIEWER` 只能读取；不同账本互不影响。Android 默认账本在云同步时合并本地分类，
包括尚未用于交易的自定义分类。网页打开记账/分类管理窗口或重新获得焦点时重新读取目录。
分类读取失败会显示重试入口，不会悄悄回退到另一套固定选项。
目前云端分类支持新增，不开放重命名、改图标或删除，避免只改动某个设备而导致目录不一致；
Android 完全本地、尚未绑定云同步账号的账本仍可编辑分类。

## 账号隐私口令

口令与“登录后解锁”“展示收入前验证”开关通过 `/api/privacy/*` 跟随登录账号，
在不同设备和浏览器通用。此设置不随家庭账本共享，也不会修改 Microsoft 登录方式。
口令由服务端验证；浏览器不再持久保存口令校验值。清除浏览器数据不会关闭账号口令。

首次升级时，原浏览器会将旧版本机的加盐校验值迁移到尚未配置的云端账号，然后清理该账号的
旧本机记录。云端已有设置（包括明确关闭口令）时始终以云端为准，不会被旧浏览器覆盖。
因此，要沿用原有本机口令，需要先在原浏览器打开一次新版网页。

网页在读取账号隐私设置前不会加载账本；读取失败也不会放行。开启收入展示前重新确认设置，
页面重新获得焦点及可见时的每分钟也会刷新；其他设备修改设置后，已有解锁状态失效。
收入和结余仍默认隐藏，并在展示五分钟后自动隐藏。修改或关闭已有口令必须输入当前口令。

这是 Web 页面的额外隐私锁，不是对账本数据的加密，也不替代 Entra 身份认证或 API 账本权限。
部署时必须先应用后端迁移并发布分类、账号隐私接口，再发布 Web。

## 交易时间

新增记录使用所选本地日期和保存时刻，不再强制写成中午或 UTC 日期。编辑已有交易时保留原有时分秒；
列表按交易时间、创建时间和稳定 ID 倒序排列，不会因修改备注而将旧记录挪到最新记录之前。
