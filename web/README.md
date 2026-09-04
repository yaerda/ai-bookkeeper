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

家庭接口按规划契约实现。部署到尚未提供家庭接口的环境时，主账本同步仍可使用，但家庭面板会显示相应 API 错误。
