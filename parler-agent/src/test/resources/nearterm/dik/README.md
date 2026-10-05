# DIK reference fixture kit

Status: DIK-0 scaffolding. Independent reference/synthetic matrices for G1/G2/G4 land here in
DIK-2/DIK-3/DIK-4. Expectations must be computed outside production formula code.

Planned layout (fill in later slices):

| Path | Owner slice | Contents |
|---|---|---|
| `g1/` | DIK-2 | outlier / change-point / I-MR references |
| `g2/` | DIK-3 | alignment / correlation / OLS / lag-scan |
| `g4/` | DIK-4 | trend / threshold_crossing outcome matrix |

Vocabulary and precedence locks live as Java tests under
`com.thingworx.things.agent.analysis` (`Dik0VocabularyLockTest`,
`ThresholdCrossingOutcomeResolverTest`). DIK-1 shared-runtime locks:
`Dik1RuntimeTest`, `stats/StableStatsTest`, `stats/LinearPercentileTest`.
