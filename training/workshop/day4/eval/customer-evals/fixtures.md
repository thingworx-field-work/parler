# Customer eval fixtures

Record the App-specific data assumptions here before running live evals.

| Fixture | Purpose | How to verify | Env flag |
|---|---|---|---|
| known Thing A | identity resolution | Thing exists and display name is stable | none |
| known numeric property | trend query | property is logged and has recent data | `CUSTOMER_HAS_HISTORY=1` |
| empty DataTable | empty success behavior | table exists, returns zero rows | `CUSTOMER_HAS_EMPTY_DATATABLE=1` |
| no-alert asset pair | no-alert workflow branch | both assets have no current alerts | `CUSTOMER_HAS_NO_ALERT_PAIR=1` |
| protected property/service | protection baseline | controlled `BaseTypes.PASSWORD` fixture exists | `CUSTOMER_HAS_PROTECTED_FIXTURE=1` |

Do not assert exact live values unless the fixture is intentionally stable.
