# AI Bookkeeper Sync API

Azure Functions Node.js v4 API for offline-first ledger synchronization.

## Endpoints

- `GET /api/health`
- `POST /api/sync/push?ledgerId={optional-ledger-id}`
- `GET /api/sync/pull?cursor=0&limit=200&ledgerId={optional-ledger-id}`
- `GET /api/family/ledgers`
- `POST /api/family/ledgers`
- `GET /api/family/members?ledgerId={optional-owned-ledger-id}`
- `POST /api/family/invitations?ledgerId={optional-owned-ledger-id}`
- `POST /api/family/invitations/{id}/accept`
- `PATCH /api/family/members/{id}?ledgerId={optional-owned-ledger-id}`
- `DELETE /api/family/members/{id}?ledgerId={optional-owned-ledger-id}`
- `PATCH /api/family/settings?ledgerId={optional-owned-ledger-id}`

The sync endpoints require an Entra External ID bearer token containing the
`sync.readwrite` delegated scope. The server derives ownership exclusively
from the signed token's `iss` and `sub` claims.

The original ledger keeps the owner's user UUID as its ledger UUID. The sync
endpoints accept an optional `ledgerId` query parameter. Omitting it resolves
the caller's default owned ledger, preserving legacy sync behavior. Supplying
it requires `OWNER`, `EDITOR`, or `VIEWER` access; viewers can pull but cannot
push. Family administration endpoints default the same way but only permit the
owner to select a ledger. `GET /api/family/ledgers` lists all owned and accepted
shared ledgers; `POST` creates an additional non-default owned ledger.
Changing a ledger between `PERSONAL` and `FAMILY` never changes transaction
ownership or versions. Converting to personal revokes members and invitations.

## Configuration

| Setting | Purpose |
| --- | --- |
| `AUTH_ISSUER` | Exact issuer from the External ID OpenID configuration |
| `AUTH_AUDIENCE` | API application client ID |
| `AUTH_SCOPE` | Required delegated scope |
| `AUTH_JWKS_URI` | External ID signing-key endpoint |
| `PG_HOST` | PostgreSQL Flexible Server hostname |
| `PG_DATABASE` | Database name |
| `PG_USER` | PostgreSQL Entra role mapped to the Function managed identity |
| `PG_PORT` | PostgreSQL port; defaults to `5432` |

No database password is stored. `DefaultAzureCredential` obtains a PostgreSQL
token from the Function App's system-assigned managed identity.

## Development

```shell
npm ci
npm test
npm run build
```

`npm run migrate` applies all SQL files in `migrations/` in lexical order using
the current Azure CLI login. Migrations must be idempotent. The command requires
`PG_HOST` and `PG_USER`; `PG_DATABASE` defaults to `aibookkeeper`.
`PG_RUNTIME_USER` optionally grants the Function managed identity the minimal
DML permissions needed by family tables after migrations.
