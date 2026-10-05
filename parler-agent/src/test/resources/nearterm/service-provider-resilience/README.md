# Service-provider-resilience (U7) test fixtures

SPR-5 Java matrix tests live under
`com.thingworx.things.agent.configrepo.Spr5AppProviderMatrixTest` and
`ServiceCapabilityDryRunEnforceTest`.

| Artifact | Role |
|----------|------|
| `U7DemoCapabilityProfiles` (main) | Demo READ_ONLY + MUTATING capability JSON + route-profile fixture |
| Offline matrix test | Policy / dry-run / egress classification / Provider fallback→degraded |
