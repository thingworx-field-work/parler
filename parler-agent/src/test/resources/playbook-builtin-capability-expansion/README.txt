CI fixtures for topic playbook-builtin-capability-expansion (Phase D execution slice).

User-owned dev_data/playbooks/* is not modified by the agent; mirror
minimal_alert_history.playbook.json into the configuration repository
(or dev_data) when updating cross_asset_pair_health to call query_alert_history.
The fixture includes evidence.table columns so llm_summary evidence is not label-only.
