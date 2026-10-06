# Import the complete SCPA utilization sample

The course builds an agent for the SCPA utilization scenario step by step. This appendix imports the **finished**
application in one go, for example to explore it before the course, to demonstrate Parler, or to compare your own work
with it.

## Prerequisites

- A ThingWorx server with the SCPA solution installed and running ([chapter 3](./03-learning-environment-scpa.md)).
- The Parler basics: both extensions and `ParlerAgentBasic.xml` ([chapter 4](./04-import-parler-extensions.md)).
- An LLM deployment you can reach from the ThingWorx server. The sample is configured for an Azure OpenAI / Foundry
  deployment of GPT-5.5 that uses the `AzureOpenAIChatV5Provider` template.

## What the sample contains

The files are in the repository's [`dev_data/`](../../dev_data/) folder and are also attached to each release.

| File or folder | Contents |
| --- | --- |
| `sample_scpa_utilization_entities.xml` | Project `Parler_SCPA_Demo`: the LLM Provider `AzureFoundryGPT55`, the `AIAgent` Thing `SCPA_Demo_Agent`, the FileRepositories `ConfigurationRepository`, `AIDocRepository` and `AIArtifactRepository`, the helper Things `SCPA_Utilization_helper` (the services behind the utilization tools), `SCPA_Mashup_Helper` (the default agent for the SCPA mashups) and `parler_test_helper`, and the configuration of four SCPA manager Things that place Parler in the SCPA mashups. |
| `sample_scpa_utilization_agent_configuration/` | The content of `ConfigurationRepository`: taxonomies, skills, Playbooks, extended tools, the `invoke_service` policy and host contexts. |
| `sample_scpa_utilization_data/` | Optional Stream and ValueStream data exports. |
| `sample_scpa_utilization_design/` | Design notes and prompt lists. They are documentation and are not imported. |

The entities export changes the configuration of `PTC.Charts.Manager`, `PTCSC.UtilizationTWImpl.Manager`,
`PTCSC.UtilizationUI.Manager` and `PTCTS.AssetMonitoring.Manager`. Import it on a lab or demo server, not on a
production SCPA installation you have not reviewed.

## Step 1: import the entities

In Composer, use **Import > From File > Entities** and import `sample_scpa_utilization_entities.xml`.

## Step 2: correct the LLM Provider

The export does not contain real connection settings. The LLM Provider fields hold placeholders instead, and a manual
import keeps them as literal text. Open **`AzureFoundryGPT55`**, go to **Configuration**, and replace them in the
**`AzureOpenAIChatV5Settings`** table:

| Field | Placeholder in the export | Set it to |
| --- | --- | --- |
| `endpoint` | `{{{env:LLM_API_SERVER}}}` | The endpoint of your Azure OpenAI / Foundry resource |
| `apiKey` | `{{{env:LLM_API_KEY}}}` | The key for that resource |
| `deployment` | `{{{env:LLM_MODEL}}}` | The name of your model deployment |
| `apiVersion` | `{{{env:LLM_API_VERSION}}}` | The API version your endpoint expects |

Save the Thing, then run **`TestConnection`** on `AzureFoundryGPT55` and on `SCPA_Demo_Agent`. Both should return
`true`. If they do not, follow the checks in [chapter 5](./05-llm-provider-and-repositories.md).

If your model is not served through the Azure OpenAI v5 request shape, create a Provider Thing from the matching
template as in chapter 5, and set it as the **LLM API Provider** of `SCPA_Demo_Agent`.

## Step 3: upload the agent configuration

Upload the **contents** of `sample_scpa_utilization_agent_configuration/` to the **root** of `ConfigurationRepository`,
keeping the folder structure:

```text
/host-contexts/*.json
/playbooks/<id>/playbook.json
/policies/invoke_service.json
/skills/<id>/SKILL.md
/taxonomies/asset-types.json
/taxonomies/identity-types.json
/tools/extended_tools.json
```

Use the same upload method as in the course chapters. From the repository root you can also upload the whole tree in
one command, with `DEV_SERVER` and `DEV_KEY` set in `.env`:

```bash
uv run load-file-tree -i dev_data/sample_scpa_utilization_agent_configuration -t ConfigurationRepository
```

Then, on `SCPA_Demo_Agent`, run **`ValidateAgentConfigurationRepository`** to check the files, followed by
**`RefreshTaxonomyCache`** and **`RefreshPromptContextCache`** so the agent loads them.

## Step 4 (optional): import the sample data

The SCPA solution generates its own live data, so this step is not required. Import the sample data when you want the
exact datasets that the documentation, prompts and evaluation suites refer to, such as the utilization records of
September 2025 and the property history of 15 September 2026.

`sample_scpa_utilization_data/` holds one `data-0.twx` file per Stream or ValueStream:

- `<timestamp>/Streams/`: `PTCSC.UtilizationTWImpl.Utilization_SM` (utilization records), `AlertHistoryStream`,
  `AnomalyMonitorStateStream` and `PTCTS.ScoreBoard.HourlyTargetHistory_SM`, plus Parler's own `AgentMessageStream` and
  `AgentLlmCallStream` with example conversations;
- `<timestamp>/ValueStreams/`: the property history of the SCPA simulators and datasets.

In Composer, use **Import > Data** and import each `data-0.twx` you need. The Parler streams are only examples; skip
them if you do not want sample conversations in your own history.

## Check the result

- Open project `Parler_SCPA_Demo` and confirm the entities listed above.
- Open the `SCPA_Mashup_Helper` configuration and confirm that `SCPA_Demo_Agent` is the default agent.
- Open an SCPA mashup with the Parler panel and ask, for example, "Show me the utilization-state breakdown for
  ORD-Contacting-01 on 2025-09-10 as a pie chart" (this needs the sample data from step 4).

## Scripted alternative

`dev_data/parler-scpa-demo.yaml` performs the whole sample installation with `load.py` (see
[chapter 4](./04-import-parler-extensions.md), option B). It also installs the SCPA solution dataset ZIPs, pauses and
resumes the SCPA simulator timers, and initializes the solution. It downloads the entities export, the zipped agent
configuration and the zipped sample data from the latest release.

`load.py` reads `.env` and resolves local paths from the directory you run it in. Run it from a directory that holds
`.env` and an `SCPA/` folder with `ALL_DATASETS_DEV_0001_Platform.zip`, `ALL_DATASETS_DEV_1001_SCPA_Core.zip` and
`ALL_DATASETS_DEV_2001_SCPA.zip` (the SCPA solution datasets are not part of this repository).

When `load.py` imports the entities export, it replaces each `{{{env:NAME}}}` placeholder with the value of `NAME` from
`.env`, so set `LLM_API_SERVER`, `LLM_API_KEY`, `LLM_MODEL` and `LLM_API_VERSION` there and step 2 is done for you.

```bash
cd <directory with .env and SCPA/>
uv run <repository>/load.py -c <repository>/dev_data/parler-scpa-demo.yaml          # dry run
uv run <repository>/load.py -c <repository>/dev_data/parler-scpa-demo.yaml --apply
```
