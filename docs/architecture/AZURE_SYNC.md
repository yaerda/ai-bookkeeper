# Azure 账本同步设计

## 当前状态

- 本人账本以 Room 作为离线数据源，并通过 `CloudSyncManager` 与 Azure 增量同步。
  获授权的共享账本由同一套 Android 页面经 `ActiveLedgerTransactionRepository`
  在线读取，数据不会写入本人 Room。
- 登录后会先上传全部本地待同步交易，再拉取当前账户的远端变化。
- 账单页按交易实际月份展示所有本地历史数据，并提供“有记录月份 + 笔数”的快速入口。
- 当前版本没有 CSV、JSON 或数据库备份导出入口。

## 目标架构

移动端不得直接连接数据库。推荐的 Azure 结构如下：

1. **Microsoft Entra External ID**：邮箱一次性验证码登录。用户输入并验证邮箱，
   不在应用或自建数据库中保存密码。
2. **Azure Functions API**：验证 Entra access token，使用 token 中不可变的 `sub`
   作为用户身份。客户端提交的 user ID 或邮箱不能作为授权依据。
3. **Azure Database for PostgreSQL Flexible Server**：保存用户、交易变更和同步游标。
4. **WorkManager**：登录后立即同步；随后在联网时执行增量同步和指数退避重试。
5. **React Web 客户端**：登录后调用同一 Functions API；浏览器不直连 PostgreSQL。

后端位于 `backend/`，使用 Azure Functions Node.js v4。生产接口为：

- `GET /api/health`：检查 Functions 和数据库连接。
- `POST /api/sync/push`：每批最多 200 条，按 `serverVersion` 检测冲突。
- `GET /api/sync/pull?cursor=...&limit=...`：按服务端单调版本增量拉取。
- `GET /api/family/ledgers`：列出本人账本、获授权的家庭账本和待接受邀请。
- `POST /api/family/ledgers`：创建新的个人或家庭账本。
- `POST /api/family/invitations`：按已验证邮箱邀请查看者或编辑者。
- `PATCH /api/family/settings`：在个人与家庭模式之间转换。

同步和家庭管理接口均可通过 `ledgerId` 查询参数选择账本。旧客户端不传该参数时，
服务端继续解析到用户的默认账本；默认账本 ID 保持为原用户 UUID，因此升级不会改变
已有交易的归属或同步身份。

Functions 使用系统分配的托管身份连接 PostgreSQL，不保存数据库密码。PostgreSQL
保持公网关闭，通过 VNet integration、Private Endpoint 和 Private DNS 提供连接。

## 已部署的生产资源

以下资源位于个人订阅的 `rg-aibookkeeper-prod`（East Asia）：

| 资源 | 名称 |
| --- | --- |
| PostgreSQL Flexible Server | `aibookkeeper-pg-prod-yaerda` |
| 数据库 | `aibookkeeper` |
| Function App | `aibookkeeper-sync-prod-yaerda` |
| API base URL | `https://aibookkeeper-sync-prod-yaerda.azurewebsites.net/api` |
| Static Web App | `aibookkeeper-web-prod-yaerda` |
| Web URL | `https://bookkeeper.fhou.net` |
| Storage Account | `aibookkeepersayaerda` |
| VNet | `vnet-aibookkeeper-prod` |
| Functions 子网 | `snet-functions` |
| Private Endpoint 子网 | `snet-private-endpoints` |
| PostgreSQL Private Endpoint | `pe-aibookkeeper-postgres` |
| Private DNS zone | `privatelink.postgres.database.azure.com` |

PostgreSQL 的公网访问为 `Disabled`，私有地址为 `10.20.1.4`。Function App 强制
HTTPS，并使用系统托管身份 `aibookkeeper-sync-prod-yaerda` 连接数据库。该身份只有
交易表的 `SELECT`、`INSERT`、`UPDATE` 以及同步版本序列权限；家庭权限表具有最小
`SELECT`、`INSERT`、`UPDATE`、`DELETE` 权限，以便撤销成员和邀请。该身份不是数据库
管理员，且不能物理删除账单交易。

External ID 配置：

| 配置 | 值 |
| --- | --- |
| Tenant | `793e78e8-3c3c-44cb-abff-2c3c8e5e2696` |
| Android / Web public client ID | `b8f0b1ac-f397-4b36-b0c6-e557da8decac` |
| API client ID / audience | `dc183072-2c27-4ad0-a6a8-3df3b91de4ad` |
| Scope | `api://dc183072-2c27-4ad0-a6a8-3df3b91de4ad/sync.readwrite` |

Android redirect URI 配置在 Android App Registration，API App Registration 不作为
public client。`sync.readwrite` 已对租户执行管理员同意。

Web SPA 仅登记精确的 `https://bookkeeper.fhou.net`、Azure 默认站点和 localhost
开发回调，不使用通配符。登录前不调用任何账本接口。

## Android 客户端

- “设置 → Azure 云同步”使用 MSAL 单账户模式打开 External ID 邮箱验证码登录。
- MSAL 自身负责安全 token cache；应用代码和 SharedPreferences 不保存 access token。
- 登录成功且存在待同步记录时立即调度；有网络时由 WorkManager 执行，另有每 6 小时
  周期任务和指数退避。
- 每轮严格先上传所有 `LOCAL` / `PENDING_SYNC` 数据并逐条确认版本，再从持久化游标
  拉取远端增量；上传按服务端上限分为每批最多 200 条。
- 上传期间若本地记录再次变化，DAO 会更新服务端基线版本，但保留新修改的待同步状态，
  防止旧响应将新修改误标为 `SYNCED`。
- 本地待上传记录不会被远端副本覆盖；这类情况计为冲突并保留本地数据。
- 服务端版本冲突时，客户端以返回版本重新建立基线并重试一次，采用明确的本地优先策略；
  仍冲突时由 WorkManager 退避重试并在同步页显示冲突数量。
- 首次同步会把当前 Room 账本绑定到当前 MSAL account ID。退出登录不清数据；不同
  account ID 登录时无法查看或同步这一本地账本，从而避免跨账户读取和上传。未登录或
  从未绑定过账户时，本地账本照常可见。多账户独立账本需在未来加入本地 owner
  namespace 后再开放。
- 登录后首页左上角显示当前账本并允许切换。首页、统计、记账、账单和详情统一依赖
  `TransactionRepository`；活动账本适配层仅将默认本人账本路由到 Room，将其他本人
  账本和受邀共享账本路由到带显式 `ledgerId` 的 API。远端账本交易只保存在当前会话
  内，不写入默认账本的 Room 数据。
- “账本与家庭管理”页面仅负责模式转换、邀请、成员和权限，不重复实现账单明细与编辑。
- 用户可创建多个个人或家庭账本；新账本使用独立 UUID，创建后会刷新账本切换器并
  自动选中新账本。

## 家庭账本与权限

- 每个身份恰好有一个默认 `PERSONAL` 账本，也可拥有多个额外账本。默认账本 ID 等于
  用户 UUID，以兼容旧客户端；额外账本使用独立 UUID。转换为 `FAMILY` 只改变共享
  模式，不改变 `ledger_id`、`owner_id`、`syncId`、`serverVersion`、增量游标或墓碑。
- 所有者可按邮箱发送邀请。被邀请者必须使用该邮箱完成 External ID 验证并明确接受，
  服务端才创建成员关系；邮箱本身不直接授予访问权。
- `VIEWER` 只能调用 pull；`EDITOR` 可调用 pull/push；`OWNER` 还可管理成员、角色和
  模式。所有权限由 API 在数据库事务内校验，不能依赖客户端隐藏按钮。
- 家庭成员的同步请求必须显式携带 `ledgerId`。不带 `ledgerId` 的旧 Android 请求仍
  只访问本人账本，保持向后兼容。
- 家庭转个人会在同一事务中删除成员关系和未接受邀请，但绝不删除或重新编号账单。
- 当前选中账本维护独立的界面数据集合；切换账本会重新订阅本人 Room 或重新拉取指定的
  共享账本，不能合并不同账本的交易。

生产数据库至少应包含：

```sql
create table app_user (
    id uuid primary key,
    normalized_email text not null,
    created_at timestamptz not null default now()
);

create table auth_principal (
    issuer text not null,
    subject text not null,
    user_id uuid not null unique references app_user(id),
    primary key (issuer, subject)
);

create table ledger_transaction (
    ledger_id uuid not null references family_ledger(id),
    owner_id uuid not null references app_user(id),
    sync_id uuid not null,
    version bigint not null,
    payload jsonb not null,
    deleted_at timestamptz,
    updated_at timestamptz not null default now(),
    primary key (ledger_id, sync_id)
);

create index ledger_transaction_change_feed
    on ledger_transaction (ledger_id, version);
```

邮箱在服务端执行 `trim().lowercase()` 后仅作为展示和联系元数据写入
`normalized_email`，不得用于自动合并账户或授权。账本所有权严格绑定不可变的
`(issuer, subject)`；即使两个主体声明相同邮箱，也必须保持独立账本。未来如需账户
合并，必须实现单独的、显式重新验证双方身份的授权流程。

## 无损升级与首次同步

Room v3 到 v4 使用显式迁移，只对 `transactions` 增加：

- `syncId`：跨设备稳定 UUID；迁移时为每条旧交易生成不同 ID。
- `serverVersion`：服务端确认的版本，初始为 0。
- `deletedAt`：软删除墓碑；删除不会立即物理清除本地行。

禁止使用 `fallbackToDestructiveMigration()`，也禁止在登录、退出登录、同步失败或远端
返回空列表时清空本地表。

首次登录后的固定顺序：

1. 保持 Room 为界面唯一数据源。
2. 上传所有 `syncStatus != SYNCED` 的本地记录，包括升级前的 `LOCAL` 数据。
3. 服务端按 `(ledger_id, sync_id)` 幂等 upsert 并返回确认版本。
4. 只有服务端逐条确认后，客户端才能将对应记录标记为 `SYNCED`。
5. 上传完成后再使用游标拉取远端增量，并按 `syncId` 合并到 Room。
6. 空响应表示“没有远端变化”，绝不表示“删除本地数据”。

删除采用墓碑同步。服务端确认所有活跃设备都越过墓碑版本并达到保留期之前，客户端和
服务端都不得物理清理该记录。

## 冲突与安全规则

- 同一 `syncId` 的并发修改由服务端分配单调递增 `version`；客户端时间不能决定权限。
- 上传请求携带客户端已知的 `serverVersion`。版本不匹配时返回冲突及服务端副本，
  不静默覆盖。
- 每个查询和写入都必须先由 token 解析用户身份，再按所请求的 `ledger_id` 校验
  `OWNER`、`EDITOR` 或 `VIEWER` 权限并过滤。
- Access token 存在 Android Keystore 支持的安全存储中；数据库凭据只存在 Functions
  的托管身份或 Key Vault 中。
- 退出登录只移除 token 和远端账户关联，不删除 Room 数据。切换账户前必须明确提示，
  并使用独立账本命名空间，避免把一个人的本地数据上传到另一个账户。

## 上线门槛

远程同步不能仅靠创建 PostgreSQL 实例上线。发布前还需要确定生产 Azure
subscription/resource group/region、Entra External ID tenant、API 域名和隐私政策，
并完成真实设备上的升级迁移、断网重试、重复请求、双设备冲突、重复邮箱和退出登录测试。
