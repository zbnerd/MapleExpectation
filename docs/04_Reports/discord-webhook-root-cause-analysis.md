# Discord Webhook Notification Failure - Root Cause Analysis

**Date:** 2025-02-13
**Issue:** Discord webhook notifications not being sent despite AI SRE Service being built
**Status:** ROOT CAUSE IDENTIFIED

---

## Executive Summary

The Discord webhook notification system is **completely non-functional** due to an environment variable naming mismatch between the `.env.example` template and the Spring Boot configuration.

**Impact:** CRITICAL - All Discord alert notifications are failing silently

---

## Root Cause

### Environment Variable Name Mismatch

| File | Variable Name | Line |
|------|---------------|-------|
| `.env.example` | `DISCORD_WEBHOOK_URL` | 33 |
| `application.yml` | `ALERT_DISCORD_WEBHOOK_URL` | 523 |

**The names don't match!**

```
.env.example:    DISCORD_WEBHOOK_URL=...
application.yml:  ${ALERT_DISCORD_WEBHOOK_URL:}
```

When Spring Boot starts, it looks for `ALERT_DISCORD_WEBHOOK_URL` but finds nothing, so the webhook URL defaults to an **empty string**.

### Evidence

**From `.env.example` line 33:**
```bash
# Discord webhook URL for alerts (optional)
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/your-webhook-url
```

**From `application.yml` line 523:**
```yaml
alert:
  discord:
    webhook-url: ${ALERT_DISCORD_WEBHOOK_URL:}  # EMPTY by default!
```

---

## Affected Components

All three Discord alert implementations depend on this property:

1. **StatelessAlertService** (ADR-0345, newest)
   - Uses `DiscordAlertChannel` → `alertWebClient`
   - Injects `@Value("${alert.discord.webhook-url:}")`

2. **DiscordAlertService** (v2 package, older)
   - Uses shared `mapleWebClient`
   - Injects `@Value("${alert.discord.webhook-url:}")`

3. **DiscordNotifier** (monitoring copilot)
   - Uses `HttpClient`
   - Injects `@Value("${alert.discord.webhook-url:}")`

---

## Failure Behavior

When webhook URL is empty:

1. `DiscordAlertChannel.send()` → POST to empty URL → WebClientRequestException
2. `DiscordNotifier.sendInternal()` → `URI.create("")` → IllegalArgumentException
3. `StatelessAlertService.sendCritical()` → Passes empty URL, fails silently

The failures are caught and logged as warnings, but the alert never reaches Discord.

---

## Solution

### Option 1: Fix `.env.example` (RECOMMENDED)

```diff
# Discord webhook URL for alerts (optional)
- DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/your-webhook-url
+ ALERT_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/your-webhook-url
```

### Option 2: Change Spring property

```diff
alert:
  discord:
-   webhook-url: ${ALERT_DISCORD_WEBHOOK_URL:}
+   webhook-url: ${DISCORD_WEBHOOK_URL:}
```

**Recommendation:** Option 1, because:
- `ALERT_` prefix provides better naming convention
- Consistent with other alert-related configurations
- Less prone to conflicts with other Discord webhooks

---

## Additional Findings

### Alert System Fragmentation

Three separate Discord alert implementations exist, creating confusion:

| Service | Package | HTTP Client | Status |
|----------|----------|--------------|--------|
| StatelessAlertService | `maple.expectation.alert` | alertWebClient | NEW (ADR-0345) |
| DiscordAlertService | `maple.expectation.service.v2.alert` | mapleWebClient | OLD |
| DiscordNotifier | `maple.expectation.monitoring.copilot` | HttpClient | Monitoring |

**ADR-0345** was supposed to unify these, but the old `DiscordAlertService` still exists and may be called by some code paths.

---

## Action Items

- [ ] Fix environment variable name mismatch in `.env.example`
- [ ] Update `.env` file with actual webhook URL
- [ ] Add validation for non-empty webhook URL at startup
- [ ] Consider removing/consolidating old `DiscordAlertService`
- [ ] Add integration test for webhook URL configuration

---

## User's Webhook URL

The user provided this webhook URL that should be configured:
```
https://discord.com/api/webhooks/1469107054252920991/JO4aD55XHLalj2XRMGoCQdMFHFjrVZinMfq-PDpB2W5XbNEjESGQ_2gE9yywFT7VFOK_
```

This should be set as `ALERT_DISCORD_WEBHOOK_URL` in the environment.

---

## Related Documents

- [ADR-0345: Stateless Alert System Design](../adr/ADR-0345-stateless-alert-system.md)
- [CLAUDE.md](../CLAUDE.md)
