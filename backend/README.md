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
- `GET /api/categories?ledgerId={optional-ledger-id}`
- `POST /api/categories?ledgerId={optional-ledger-id}`
- `POST /api/categories/import?ledgerId={optional-ledger-id}`
- `GET /api/privacy/settings`
- `PATCH /api/privacy/settings`
- `POST /api/privacy/verify`
- `POST /api/privacy/migrate`

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

## Ledger categories

Category records are scoped by ledger, with a unique `(ledger_id, type, name)`.
Readers use the same membership checks as transaction reads; creating or importing
categories requires `OWNER` or `EDITOR`. A category ID is a cloud catalog ID, never
an Android Room ID. Legacy transactions keep their original category snapshots.

`GET` returns `{ categories }` with `id`, `name`, `type`, `icon`, `color`,
`sortOrder` and `isSystem`. Each ledger starts with Android's 16 defaults and also
retains valid historical custom categories. `POST` takes
`{ name, type, icon, color, sortOrder? }` and returns `{ category }`.
Names are trimmed and whitespace-normalized. Duplicate names within the same
transaction type return the existing definition rather than overwriting it.

`POST /categories/import` takes `{ categories: [...] }` (at most 200 per request)
and returns the merged catalog. It adds missing definitions without replacing
server metadata. Android imports its offline categories only into its own default
ledger, including unused custom categories, and pulls cloud definitions before
merging transactions. Accepted family members read the shared ledger's catalog;
their private categories are not imported into it.

## Account privacy settings

Privacy belongs to the authenticated `app_user`, not to a ledger or an email
address supplied by a client. `GET /privacy/settings` returns only
`{ initialized, hasPasscode, requireOnLogin, requireForIncome, version }`,
with `Cache-Control: no-store`; it never returns a hash or salt.

`PATCH` requires the current `version`, both privacy flags, and `currentPasscode`
when a passcode already exists. `newPasscode` creates or replaces a passcode;
`clearPasscode: true` clears it with both flags disabled. Stale versions return
409. `POST /privacy/verify` accepts `{ passcode, version }` and returns
`{ verified: true, version }` only after verification.

New passcodes use salted PBKDF2-HMAC-SHA256 with 600,000 iterations. Plaintext is
not persisted. Verification and settings changes share a per-account throttle:
five incorrect attempts lock verification for five minutes. Failed-attempt
updates must commit even when the HTTP result rejects the passcode.

`POST /privacy/migrate` imports the legacy browser's 120,000-iteration verifier
only if no settings row exists. The old salt is a hexadecimal string encoded as
UTF-8 when deriving the key, not hex-decoded bytes. Explicitly clearing a cloud
passcode retains the settings row, so stale browsers cannot restore it.

The passcode is an additional Web privacy gate. It does not alter Entra login,
transaction API authorization, or Android authentication.

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
`PG_RUNTIME_USER` optionally grants the Function managed identity the DML
permissions needed by family, category and account-privacy tables, plus category
identity-sequence access. Apply migrations `007` and `008` and deploy the API
before deploying clients that use the new endpoints.
