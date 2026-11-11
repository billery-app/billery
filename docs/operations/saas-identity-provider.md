# SaaS: identity provider

> **Audience:** operators of a SaaS deployment (Spring profile `saas`).
> **Scope:** which settings connect the application to an OpenID Connect provider, what the
> provider has to offer, and how to **exchange** the provider without a cut-over. For the design
> see `docs/architecture/multi-tenancy.md`.

## What the application expects

The application never stores passwords. It trusts signed tokens (JWT) of the providers you list and
reads only these claims: `iss`, `sub`, `aud`, `exp`, `email`, `email_verified` and `name`.
Everything else, in particular **who belongs to which tenant with which role**, lives in the
application's own tables (`user_identity`, `tenant_member`), never in claims.

| Setting | Meaning |
|---------|---------|
| `BILLERY_SECURITY_ISSUERS` | Comma-separated issuer URLs whose tokens are accepted. The provider's OIDC discovery document (`<issuer>/.well-known/openid-configuration`) must be reachable from the backend. |
| `BILLERY_SECURITY_AUDIENCE` | The `aud` a token must carry. **Required**: the application refuses to start in the `saas` profile without it. |
| `BILLERY_OIDC_CLIENT_ID` | The single-page app's client id at the provider. |
| `BILLERY_OIDC_ISSUER` | The provider the app signs in at (default: the first issuer). |
| `BILLERY_OIDC_SCOPE` | Default `openid profile email offline_access`. |

The provider needs one **public client** for the app: authorization-code flow with PKCE, redirect
URI `https://<your host>/auth/callback`, post-logout redirect `https://<your host>/`, refresh
tokens for public clients (the silent renewal), the `email` and `email_verified` claims in the
**access token**, and the audience if you set one. Users must have a **verified** e-mail address
at the provider (the first sign-in is refused otherwise).

## Open sign-up

The application lets any signed-in person create a company, so whoever can register at the
provider can use it. Before self-registration (or social logins such as Google) is switched on at
the provider:

- **Verified e-mail.** Accounts must verify their address before they can sign in, or the token
  must carry `email_verified: false` until they do: an unverified address is never linked to an
  existing user and cannot accept an invitation, but it could still sign up.
- **Bot protection** on the registration form (CAPTCHA or the provider's own bot detection) and
  the provider's own rate limits for registration and sign-in.
- **Disposable addresses:** block known throwaway domains if the provider offers it.
- The application's own limits stay in force regardless of the provider: companies per user, per
  source IP and per day, invitation mails per user, recipient, source IP and day, requests per
  tenant ([`saas-tenant-lifecycle.md`](./saas-tenant-lifecycle.md#limits-against-abuse-171)).

The checklist for the switch is in
[`saas-tenant-lifecycle.md`](./saas-tenant-lifecycle.md#before-opening-public-sign-up).

## How a user is recognised

A token is mapped to an application user by `(issuer, subject)` in `user_identity`:

1. a known pair is the user, whatever the e-mail claim says;
2. an unknown pair needs `email_verified = true`; the address is then matched, case-insensitively,
   to the existing user with that address (the identity is **linked** to it) or a new user is created;
3. an unverified address never links and never creates a user.

Rule 2 is what makes an exchange possible, and rule 3 is what keeps anyone from taking over an
account by registering its address at a provider that does not check it. **Only use providers that
verify e-mail addresses** (or that you control completely).

## Exchanging the provider

Nothing is migrated in the database: the existing users and memberships stay, only new identity
rows are added. The old provider keeps working until you remove it.

1. **Prepare the new provider.** Create the app client (see above) and make sure the users exist
   there with the **same, verified** e-mail addresses (import them, or let them register). Check
   that its tokens carry `email`, `email_verified` and the audience.
2. **Accept both.** Set `BILLERY_SECURITY_ISSUERS=<old>,<new>`, restart. Tokens of both are valid;
   the app still signs in at the old one (`BILLERY_OIDC_ISSUER` unchanged).
3. **Check one account.** Sign in once through the new provider (point `BILLERY_OIDC_ISSUER` and
   `BILLERY_OIDC_CLIENT_ID` at it on a test instance, or call the API with a token of the new
   provider). `GET /api/me` must show the same user id and the same tenants as before.
   In the database, the user has now two rows in `user_identity`:

   ```sql
   SELECT issuer, subject FROM user_identity WHERE user_id = '<user id>';
   ```
4. **Switch the app.** Set `BILLERY_OIDC_ISSUER` and `BILLERY_OIDC_CLIENT_ID` to the new provider,
   restart. Users sign in at the new provider; the first sign-in of each links their identity.
   Sessions at the old provider simply end (the app keeps its tokens in memory only).
5. **Remove the old one.** When all active users have signed in once at the new provider
   (`user_identity` has a row for the new issuer for each of them), set
   `BILLERY_SECURITY_ISSUERS=<new>` and restart. Tokens of the old provider are refused from then on.
6. **Clean up.** Optionally remove the stale identities of the old issuer:

   ```sql
   DELETE FROM user_identity WHERE issuer = '<old issuer>';
   ```

   Users who never signed in at the new provider keep their account and tenants but cannot sign in
   until they do (their identity at the new provider is linked on that first sign-in).

If an address changed during the exchange, the user cannot be linked automatically. Invite the new
address to the tenants (Einstellungen → Team & Zugriff) or, as an operator, update
`app_user.email` before the first sign-in at the new provider.

## Tested

`IdpSwitchTest` plays these steps with real signed tokens of two mock providers through the same
issuer registry as the application: only the old provider, both, then only the new one; the user
and the tenant membership stay the same, the old provider's tokens are refused at the end, an
unverified address does not link, and a token meant for another application is refused.
`SaasApiTest` covers a second provider reaching the same data over HTTP.
