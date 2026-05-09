# Exposing the archive service to friends via Cloudflare Tunnel + Access

This is the opt-in path for sharing your backup server with friends over the
public internet without opening a port on your home router. LAN access (and
Tailscale, if you use it) keep working independently — the tunnel is purely
additive.

The properties you get:

- **Outbound-only.** `cloudflared` opens a connection from inside the LXC out
  to Cloudflare; nothing inbound is needed.
- **TLS terminated by Cloudflare.** No certs to manage.
- **Email-allowlist auth at the edge.** Friends sign in once with a magic link
  to a Google/email address you've allowlisted. Anyone who guesses the
  hostname without an Access cookie gets 403'd before they ever touch your
  FastAPI bearer.
- **Not searchable.** Pick a long random subdomain (e.g.
  `aio-x9k7m.yourdomain.tld`); it's not in any zone listing, and the
  `X-Robots-Tag: noindex` header (set in the Access app) keeps it out of
  search indexes.
- **App keeps working.** The Android app injects two service-token headers
  (`CF-Access-Client-Id` / `CF-Access-Client-Secret`) on every request when
  configured, so it bypasses the email-login screen but still passes Access.
  The bearer token continues to protect the FastAPI handlers underneath.

## One-time Cloudflare dashboard setup

You need a Cloudflare account with a domain on it (free tier is enough).

### 1. Create the tunnel

1. Cloudflare dashboard → **Zero Trust** → **Networks** → **Tunnels** →
   **Create a tunnel** → name it `odyssey-archive`.
2. Copy the **token** Cloudflare shows you (starts with `eyJ...`). This goes
   into `archive-service/.env` as `CLOUDFLARED_TUNNEL_TOKEN=...`.
3. Under **Public Hostname**, add:
   - **Subdomain:** something long and non-obvious — e.g.
     `aio-x9k7m` (a couple of random characters keeps it out of dictionary
     enumeration).
   - **Domain:** your zone.
   - **Service type:** `HTTP`.
   - **URL:** `archive:8088` (the docker-compose service name, port 8088).

### 2. Create the Access application

1. Zero Trust → **Access** → **Applications** → **Add an application** →
   **Self-hosted**.
2. **Application domain:** match the hostname you set in step 1.
3. **Identity providers:** keep the default One-time PIN (email magic link),
   or add Google/GitHub if you prefer.
4. **Policies → Add a policy** → name it "Friends":
   - **Action:** Allow.
   - **Include / Emails:** add each friend's email.
5. **Add another policy** → name it "Phone app":
   - **Action:** Service Auth (bypasses identity-provider login).
   - **Include / Service Token:** create one named `odyssey-android`. Copy
     the **Client ID** and **Client Secret** that pop up — they go into the
     Android app's Settings → Cloudflare Access (advanced) section, or you
     paint a QR with them so friends just scan once.

### 3. Optional hardening on the Access application

- **Settings → CORS:** leave restrictive (the Android app doesn't issue CORS
  preflights).
- **Settings → Custom rules → Add rule:** set response header
  `X-Robots-Tag: noindex, nofollow` so even if a friend pastes the URL
  somewhere it won't get crawled.

## On the LXC

Once the dashboard side is configured:

```bash
# Paste the tunnel token from step 1 into .env
echo 'CLOUDFLARED_TUNNEL_TOKEN=eyJ...' >> /opt/archive-service/.env

# Bring it up — the up.sh script auto-detects the token and adds the
# cloudflared sidecar to the compose stack.
/opt/archive-service/scripts/up.sh
```

The cloudflared service runs alongside `archive`, restarts on its own,
and uses Docker's internal DNS (`http://archive:8088`) to reach the
FastAPI app — so neither container needs a public port published.

## Removing public exposure later

Comment out `CLOUDFLARED_TUNNEL_TOKEN` in `.env`, re-run `scripts/up.sh`,
then delete the tunnel and Access app from the dashboard. The archive
service keeps serving on the LAN (and over Tailscale) exactly as before.

## Issuing tokens per friend (optional)

For per-friend revocation, create one Service Token per friend in the
Access policy and share each pair via QR. Revoking one friend = deleting
that one token from the dashboard, no impact on others.
