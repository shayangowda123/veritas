# ADMIN Account Provisioning — Operator Runbook

## Purpose

This mechanism allows authorized infrastructure operators to create the initial
ADMIN account (or additional ADMIN accounts) without exposing any HTTP endpoint
or reopening public registration for the ADMIN role.

The provisioning process:
- Runs as a **standalone CLI entry point** — no HTTP server is started.
- Is **disabled by default** — the runner bean is never created unless
  explicitly enabled via a command-line flag.
- Reads credentials exclusively from **environment variables**, never from
  command-line arguments, to prevent exposure in process listings (`ps aux`).

---

## Required Environment Variables

| Variable | Purpose | Required |
|----------|---------|----------|
| `ADMIN_PROVISIONING_EMAIL` | Email address for the ADMIN account | Yes |
| `ADMIN_PROVISIONING_PASSWORD` | Plaintext password (hashed immediately; never stored/logged) | Yes |
| `POSTGRES_PASSWORD` | Database password for the datasource | Yes |
| `SECURITY_JWT_SECRET_BASE64` | Only consumed by the normal web app; not needed during provisioning (but present in application.yml — may emit unresolved placeholder warnings that are harmless) | No |

If `ADMIN_PROVISIONING_EMAIL` or `ADMIN_PROVISIONING_PASSWORD` is absent or
blank, the provisioning exits immediately with code **1** without modifying
any data.

---

## Execution

### Linux / macOS

```bash
# 1. Build without running tests (skip if already compiled)
cd backend
./mvnw package -DskipTests -q

# 2. Provision — credentials injected via env vars, NOT command-line args
ADMIN_PROVISIONING_EMAIL="admin@yourdomain.com" \
ADMIN_PROVISIONING_PASSWORD="$(read -s -p 'Password: ' pw; echo $pw)" \
POSTGRES_PASSWORD="<db-password>" \
  java -cp "target/classes:target/dependency/*" \
  com.veritas.provisioning.AdminProvisioningApplication \
  --app.admin-provisioning.enabled=true
```

### Windows (PowerShell)

```powershell
# Securely prompt for password (not stored in history)
$pw = Read-Host -AsSecureString "ADMIN password"
$plain = [System.Net.NetworkCredential]::new('', $pw).Password

$env:ADMIN_PROVISIONING_EMAIL = "admin@yourdomain.com"
$env:ADMIN_PROVISIONING_PASSWORD = $plain
$env:POSTGRES_PASSWORD = "<db-password>"

java -cp "target/classes;target/dependency/*" `
  com.veritas.provisioning.AdminProvisioningApplication `
  --app.admin-provisioning.enabled=true
```

> **Alternative:** Use a secrets manager (HashiCorp Vault, AWS SSM, Azure Key Vault)
> to inject `ADMIN_PROVISIONING_EMAIL` and `ADMIN_PROVISIONING_PASSWORD` into
> the process environment at execution time.

---

## Exit Codes

| Code | Meaning |
|------|---------|
| 0    | ADMIN created successfully, or already existed (idempotent) |
| 1    | Invalid input: missing/blank credentials, bad email or password format |
| 2    | Email belongs to a non-ADMIN account — promotion refused |
| 3    | Concurrent provisioning conflict — manual verification required |

---

## Verifying Successful Creation

After exit code 0, log in via the normal web application authentication flow:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@yourdomain.com","password":"<the-password>"}'
```

A successful response includes a JWT. The user's role claim will be `ADMIN`.

You can also verify via direct database query:

```sql
SELECT id, email, role, is_verified FROM users WHERE role = 'ADMIN';
```

---

## Idempotent Re-execution

Running the provisioning command multiple times with the same email is safe:
- If the account already exists with ADMIN role: reports "already exists"
  and makes no changes.
- The existing password hash is NEVER overwritten.

---

## Role Conflict Handling

If the email belongs to an existing OWNER or RENTER account:
- Provisioning refuses to proceed (exit code 2).
- The existing account data (role, password, phone, verification) remains
  completely unchanged.
- To promote an existing user, a separate reviewed database migration
  process is required. This tool intentionally does not support that.

---

## Security Notes

1. **Never enable the provisioning flag for normal web application startup.**
   `--app.admin-provisioning.enabled=true` must ONLY be passed to the CLI
   entry point (`AdminProvisioningApplication`), never to the web application
   (`VeritasApplication`).

2. **Disable/remove the feature after provisioning:**
   - Do not add the property to `application.yml` or any profile file.
   - If deployed in a container, ensure the env vars are removed from the
     container spec after the provisioning task completes.
   - There is no persistent effect — the feature only exists for the
     duration of the CLI invocation.

3. **Avoid credential exposure:**
   - Use `read -s` (Linux) or `Read-Host -AsSecureString` (Windows) to avoid
     passwords in shell history.
   - Never pass passwords as command-line arguments (visible in `/proc`).
   - Rotate secrets in your vault/CI after provisioning.

4. **No HTTP attack surface:** The provisioning entry point starts
   `WebApplicationType.NONE` — no Tomcat, no endpoints, no filter chain.

---

## Disabling / Removing

To ensure the feature cannot accidentally activate:

1. Do NOT set `app.admin-provisioning.enabled` in any `application.yml` or
   environment that the web application reads.
2. The runner bean uses `@ConditionalOnProperty` with `matchIfMissing=false` —
   absence of the property is the secure default.
3. If you wish to remove the capability entirely (after initial provisioning):
   - Delete the `com.veritas.provisioning` package.
   - The existing ADMIN user(s) in the database remain intact.

---

## Operational Limitations

- The CLI must have network access to the PostgreSQL database.
- In containerized deployments, run the provisioning command in an ephemeral
  `Job` / `runTask` (Docker Compose, Kubernetes Job, Flyway-style pre-deploy hook)
  rather than inside the long-lived application container.
- This mechanism provisions a single ADMIN account per invocation. For multiple
  administrators, run the command sequentially or in parallel with different
  email/password pairs.
