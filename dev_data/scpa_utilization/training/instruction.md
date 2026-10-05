# Parler Agent — hands-on setup (SCPA Utilization lab)

**Purpose:** Step-by-step lab checklist for training. Use the **scpa_utilization** test environment to learn how to install ThingWorx, deploy Parler, and configure an **AgentThing** before you extend skills, playbooks, and tools.

**Audience:** Colleagues setting up Parler for the first time.

---

## 1. Install ThingWorx and import extensions

1. Install ThingWorx on your lab server (follow your team’s standard TWX install guide).
2. Import the SCPA solution extensions, then the two Parler extensions.  
   Reference import list (same order as the `import_extension` step of `dev_data/import_scpa_ootb.yaml`):
   - `parler-agent/build/parler-agent.zip`
   - `parler-ui-widget/dist/parler-ui-widget.zip`
   - `dev_data/SCPA/ALL_DATASETS_DEV_0001_Platform.zip`
   - `dev_data/SCPA/ALL_DATASETS_DEV_1001_SCPA_Core.zip`
   - `dev_data/SCPA/ALL_DATASETS_DEV_2001_SCPA.zip`
3. Confirm all extensions show as **installed** in ThingWorx Composer.

---

## 2. Create an LLM Provider

1. In Composer, create an **LLM Provider** entity.
2. Pick the **Provider Template** that matches the model you have (OpenAI, Azure OpenAI, Anthropic, etc.).
3. Set **`API_KEY`** and **`deployment`** (or equivalent) for your account.
4. Leave all other fields at **OOTB defaults**.
5. Open the **Services** tab → run **`Test Connection`**.  
   - **Pass:** result is **`true`** → the platform can reach the LLM.

---

## 3. Create an AgentThing

1. Create a new **Thing** from the **AgentThing** template (e.g. name it for your lab).
2. On the **Configuration** page → **LLM Provider**, select the provider from step 2.
3. Open **Services** → run **`Test Connection`**.  
   - **Pass:** test succeeds (agent can use that provider).

---

## 4. Dedicated File Repository for the Artifact Cache (required)

1. Create a dedicated **File Repository** for Artifact Cache payloads (example name:
   **`AIArtifactRepository`**).
2. On the AgentThing **Configuration** page, set **Artifact Cache File Repository** to that
   repository.
3. Edit/save the AgentThing after setting it. A blank or unavailable repository rejects user
   turns; there is no in-memory fallback.
4. If the lab needs table CSV export, configure a separate **Export File Repository**. That
   optional setting is not the Artifact Cache repository.

---

## 5. File Repository for agent configuration files

1. Create a second **File Repository** for agent assets (skills, playbooks, taxonomies, etc.).  
   Example name: **`ConfigurationRepository`**.
2. On the AgentThing, set **Configuration Repository** to that repository.

*(Later labs use `dev_data/import_scpa_utilization.yaml` to upload skills and playbooks into this repository.)*

---

## 6. Other AgentThing settings

Leave remaining configuration fields at **OOTB defaults** unless your lab guide says otherwise.

---

## 7. AgentThreadDataTable row

1. Open the **AgentThreadDataTable** (or your site’s equivalent data table).
2. Add one row with at least:
   - **`conversationId`** — unique id for this lab thread (any stable string you choose).
   - **`username`** — **must be your ThingWorx login username** (exact match).
   - **`agentName`** — the **name** of the AgentThing you created in step 3.

These three fields are required for the Parler UI to bind a conversation to your agent.

---

## 8. Open the Parler mashup and connect

1. Import / open the **Parler** mashup (from your environment import, e.g. `dev_data/Mashups_Parler.xml` if not already present).
2. In the mashup:
   - **Conversation** dropdown — select your `conversationId`.
   - **Agent** dropdown — select your agent.
3. Click **Connect**.
4. **Pass:** the dialog area shows **`Transport: connected`**.  
   You can start chatting.

---

## 9. Smoke test

1. Send: **`who are you?`**
2. **Expected style of reply** (wording may vary slightly):

   > I'm your ThingWorx AI assistant. I can help you explore entities, query assets, inspect properties and services, analyze cached tabular results, check alerts, and work with history/chart data in your ThingWorx platform.

3. Click the **Info** button on the assistant turn.  
   - **Pass:** you see turn metadata (tokens, timing, charts/tables counts, etc.).

---

## What’s next (not in this lab)

After this OOTB path works:

- Load utilization **extended tools**, **skills**, and **playbooks** into **ConfigurationRepository** (see `dev_data/import_scpa_utilization.yaml` and `implementation_guide-EN.md`).
- Run utilization prompts from `utilization_prompts.txt` and validate charts/tables in the UI.

---

## Quick reference

| Step | Check |
|------|--------|
| Extensions | Parler agent + widget + SCPA zips installed |
| LLM Provider | `Test Connection` → `true` |
| AgentThing | LLM selected; `Test Connection` OK |
| Repositories | Export + Configuration repositories set |
| Data table | `conversationId`, **your** `username`, `agentName` |
| Mashup | Connected; `Transport: connected` |
| Chat | `who are you?` + Info panel |
