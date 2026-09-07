# Coordinator Upgrade / Rollback Runbook

Operational guide for upgrading the `trino-opa-access-control` plugin on a Trino
coordinator and rolling back if something goes wrong. Applies to the plugin jar
**and** its runtime dependencies (Jackson, Caffeine, Micrometer, airlift).

> **Fail-closed reminder:** the plugin denies access on any OPA error, malformed
> response, or invalid SQL. An upgrade that breaks the plugin or its config will
> manifest as **every query denied** — not as an outage with errors. Treat a
> sudden "Access Denied" for everything as a plugin/OPA problem, not a policy
> change.

## Layout

A Trino plugin lives in its own subdirectory of the coordinator's `plugin/`
directory. The plugin classloader is isolated, so the directory must contain the
plugin jar **and** its runtime deps — but **not** `trino-spi`/`trino-parser`
(the coordinator provides those).

```
<trino-home>/plugin/opa-access-control/
  trino-opa-access-control-<v>.jar        # the plugin (unshaded)
  jackson-databind-*.jar                  # runtime deps
  caffeine-*.jar
  micrometer-core-*.jar
  airlift-configuration-*.jar
  ...                                     # never the -conformance-cli jar
```

`deploy/install.sh` builds and populates this directory correctly.

## Upgrade

1. **Build the new plugin + deps** on a machine with JDK 23+:
   ```bash
   ./deploy/install.sh --coordinator <user@host>   # or local install
   ```
   This replaces the jars in `<trino-home>/plugin/opa-access-control/`.

2. **Review config.** Compare `deploy/access-control.properties.example` with
   the coordinator's `etc/access-control.properties`. New `opa.*` keys added in
   the upgrade must be present if you want the new behavior; unknown keys fail
   fast, so a stale config with a removed key will prevent startup.

3. **Validate the policy bundle** against the new plugin before/while rolling
   out (the conformance gate):
   ```bash
   java -jar trino-opa-access-control-<v>-conformance-cli.jar conformance \
       --policy-dir /path/to/policies --mode safe
   ```
   Exit 0 = the policy responses are well-formed for the new plugin. This does
   **not** judge decision correctness — that stays with policy tests + review.

4. **Restart the coordinator.** Trino loads plugins at startup; there is no
   hot-reload for access-control plugins.

5. **Smoke test.** Confirm the coordinator starts, then run a known-allowed and
   a known-denied query. Because the plugin fails closed, a healthy start plus
   the expected allow/deny behavior is the signal the upgrade is good.

## Rollback

Because the plugin directory is just jars + a config file, rollback is a file
swap + restart:

1. **Keep the previous jars.** Before upgrading, snapshot the plugin directory:
   ```bash
   ssh <user@host> 'tar -C <trino-home>/plugin -czf /tmp/opa-plugin-before-<v>.tgz opa-access-control'
   ssh <user@host> 'cp <trino-home>/etc/access-control.properties /tmp/access-control.properties.before-<v>'
   ```

2. **Restore on failure.** If the upgrade breaks startup or causes unexpected
   denials:
   ```bash
   ssh <user@host> 'rm -rf <trino-home>/plugin/opa-access-control && \
     tar -C <trino-home>/plugin -xzf /tmp/opa-plugin-before-<v>.tgz'
   ssh <user@host> 'cp /tmp/access-control.properties.before-<v> <trino-home>/etc/access-control.properties'
   ssh <user@host> 'systemctl restart trino'   # or your init system
   ```

3. **Verify rollback** with the same smoke test (known-allowed + known-denied).

## Notes

- **Rolling upgrade across a cluster:** Trino coordinators are typically a small
  fleet. You can upgrade one coordinator at a time; because decisions are cached
  per coordinator and OPA is the source of truth, a mixed fleet (old + new
  plugin) is safe as long as the policy bundle is compatible with both.
- **Policy/plugin version skew:** the conformance CLI (`--schema-version`) is the
  mechanism for validating a policy against a future/forked supported version
  before a coordinated rollout.
- **Never deploy the `-conformance-cli` jar** into the plugin directory — it is
  the CI gate, not a runtime component.