# Hierarchy: five network services on `AgentThing`

## Symptom

Prompt example:

> Compare **stacking robots** in **USA** vs **Germany**.

Without hierarchy, the agent either **over-queries** globally or treats **region labels** as if they were **Thing names** (and fails with hierarchy resolve errors).



We can start with a sample prompt.

```
How many stacking robots do you have?
```

With the taxonomy settings in the previous step, the LLM can answer this one easily.



However, if we want to add a scope like the one below:

```
How many stacking robots are there in USA?
```

The LLM will eventually fail at the hierarchy node query.

<img src="./__images__//image-20260531151418139.png" alt="image-20260531151418139" style="zoom:50%;" />



and therefore, it can't give you an answer at the end.

<img src="./__images__//image-20260531151442100.png" alt="image-20260531151442100" style="zoom:50%;" />



## Solution

On your `AIAgent` Thing, there are 5 services related to hierarchy that you can override, in order, to support your application. 

The 5 services to be overridden in order are:

1. `GetRootNode`: How to identify the root node of your hierarchy
2. `GetFlattenNameDescription`
3. `ResolveNetworkID`
4. `GetChildNodes`
5. `GetAssetList`



You have to make sure all 5 services have been overridden correctly.

<img src="./__images__//image-20260531153640090.png" alt="image-20260531153640090" style="zoom:33%;" />



### GetRootNode

In the SCPA example, indeed there are two root nodes. From the screenshot, it's easy to figure out that we need the root node with name: `PTC`.

<img src="./__images__//image-20260531152925053.png" alt="image-20260531152925053" style="zoom:50%;" />


```JS
var network="PTCTDD.Cellfab.AssociationNetwork_NW";

// result: INFOTABLE dataShape: "NetworkConnection"
let query_result = Networks["PTCTDD.Cellfab.AssociationNetwork_NW"].GetNetworkConnections({
	maxDepth: 1 /* NUMBER */
});

let result=DataShapes["HierarchyNode_DS"].CreateValues();
query_result.rows.toArray().forEach(row=>{
  let thing_name=row['to'];
  if(Things[thing_name].PTCDisplayName === 'PTC'){
      let newEntry = {};
      newEntry.id=thing_name;
      newEntry.name=Things[thing_name].PTCDisplayName;
      result.AddRow(newEntry);
  }
});
```



### GetFlattenNameDescription

This service is to build a lookup table for the LLM to query by a node label.

<img src="./__images__//image-20260531152637387.png" alt="image-20260531152637387" style="zoom:50%;" />

```JS
let result=DataShapes["HierarchyNode_DS"].CreateValues();

let query_result = Networks["PTCTDD.Cellfab.AssociationNetwork_NW"].GetNetworkConnections({
	maxDepth: undefined /* NUMBER */
});

query_result.rows.toArray().forEach(row=>{
  let thing_name=row['to'];
  let newEntry={};
  newEntry.id=thing_name;
  newEntry.name=Things[thing_name].PTCDisplayName;
  result.AddRow(newEntry);
});
```

### ResolveNetworkID

This service is to help the LLM look up the correct network ID based on the network node label.

**`Caution`**: In a real production environment, this service can be drastically optimized for performance by using the `CacheThing`.

<img src="./__images__//image-20260531153052063.png" alt="image-20260531153052063" style="zoom:50%;" />



```JS
//this service will be optimized from 2 sides in the future:
//1. using cachething to cache the flattened name description infotable;
//2. using regex to search the name

let result=DataShapes["HierarchyNode_DS"].CreateValues();

// result: INFOTABLE dataShape: "HierarchyNode_DS"
let query_result = me.GetFlattenNameDescription();

query_result.rows.toArray().forEach(row=>{
  let newEntry={};
  if(row.name===name){
    newEntry.id=row.id;
    newEntry.name=row.name;
    result.AddRow(newEntry);
  }
  
});
```

### GetChildNodes

This service is to help the LLM find all child nodes underneath a specific network ID.

<img src="./__images__//image-20260531153721259.png" alt="image-20260531153721259" style="zoom:33%;" />


```JS
let result=DataShapes["HierarchyNode_DS"].CreateValues();

let query_result = Networks["PTCTDD.Cellfab.AssociationNetwork_NW"].GetSubNetworkConnections({
	maxDepth: undefined, /* NUMBER */
    start: id
});

query_result.rows.toArray().forEach(row=>{
  let thing_name=row['to'];
  let newEntry={};
  newEntry.id=thing_name;
  newEntry.name=Things[thing_name].PTCDisplayName;
  result.AddRow(newEntry);
});
```

### GetAssetList

This service helps the LLM get the concrete asset list based on a given network ID.

<img src="./__images__//image-20260531153808271.png" alt="image-20260531153808271" style="zoom:50%;" />


```JS
let query_result=me.GetChildNodes({
	id: id /* STRING */
});
let result=DataShapes["EntityList"].CreateValues();
let assetList=[];
let IAMEntryPoint = "PTCTS.IAMAdmin.EntryPoint";
	let IAMManager = Things[IAMEntryPoint].GetConfiguredComponentManager();

query_result.rows.toArray().forEach(row=>{
  let thingName=row['id'];
  if (Things[thingName] && Things[thingName].ImplementsShape({
						thingShapeName: "PTCTS.IAMAdmin.ModelLogic_TS"
	})) {
    let thisAssociationName = thingName; // currently processing an association
					var associationThingGroup = Things[thingName].PTCIAMAssociationThingGroup;
					if (associationThingGroup && ThingGroups[associationThingGroup]) {
						var associationNodes = Things[IAMManager].GetThingGroupThingMembers({
							thingGroupName: associationThingGroup
						});
						associationNodes.rows.toArray().forEach(associationNode => {
							var thingName = associationNode.name;
							if (thisAssociationName !== thingName) {
								// Include without configuration check
								if (assetList.includes(thingName) == false) {
									let newEntry = {};
									newEntry.name = thingName; // STRING
									newEntry.description=Things[thingName].PTCDisplayName;
									result.AddRow(newEntry);
									assetList.push(thingName);
								}
//							} else {
//								// Only add associations to the results if they are configured in the asset model
//								// This is unusual, and only happens when you want to include assoctions as assets for display
//								if (assetList.includes(thingName) == false && me.IsConfiguredThing({
//										thingName: thingName
//									})) {
//									let newEntry = {};
//									newEntry.name = thingName; // STRING
//									newEntry.descriptipn=Things[thingName].PTCDisplayName;
//									result.AddRow(newEntry);
//									assetList.push(thingName);
//								}
							}
						});
					}
  }
})
```



## Test

You don't need to refresh anything for the hierarchy service update. After the update, you can cut-off the old response and submit the same prompt again. You can see that the agent can execute the `query_asset_count_under_hierarchy_node` correctly now.

<img src="./__images__//image-20260531153935224.png" alt="image-20260531153935224" style="zoom:50%;" />

<img src="./__images__//image-20260531154001756.png" alt="image-20260531154001756" style="zoom:50%;" />





## Concept

**Hierarchy** answers **where** assets live in a **network tree**. **Taxonomy** answers **what** they are. Typical flow: **resolve / expand hierarchy**, then **intersect** taxonomy query results with the bounded Thing set. The load-bearing result fields are **`intersectThingNames`**, **`preIntersectMatchCount`**, **`intersectedRowCount`**, **`queryHasMore`**, **`expandHasMore`**, and **`hasMore`** (`queryHasMore OR expandHasMore`). Maintainers can cross-check the exact wire envelope in **`CONTRACTS/API_CONTRACT.md`**.

## The five overridable services summary

Parler’s Java facade calls these **exact service names** on the **`AIAgent`** Thing (or the effective target); customers **override implementations** in ThingWorx while keeping names stable. Maintainers can cross-check the canonical table in **[`docs/architecture/hierarchy-network-services.md`](../../docs/architecture/hierarchy-network-services.md)** and the code constants in **`HierarchyNetworkServiceNames`** in this repository.

| Service | Role |
|---------|------|
| **`GetRootNode`** | Zero or one root row for current network context. |
| **`GetFlattenNameDescription`** | Bounded flat node list (`HierarchyNode_DS`) for default resolve material. |
| **`ResolveNetworkID`** | Input display **`name`** → 0..N **`HierarchyNode_DS`** rows (`id` is **`NetworkID`**). |
| **`GetChildNodes`** | Direct child **network** nodes (`HierarchyNode_DS`), not the business Thing list. |
| **`GetAssetList`** | Given node **`id`**, return **`EntityList`** (`name` + `description`) for Things attached across that node’s **subtree**. |

## Mashup side

Pass **`hostContext`** as UTF-8 JSON with **`key`** + **`context`** when the mashup should describe page scope to the agent (chapter **19**, embedding + Host Context). The server renders **repository-registered** templates into prompt text; unregistered parseable keys on **0.1.206+** get generic fenced-JSON fallback. It does **not** auto-inject scope into **`query_entities*`**. For hierarchy-scoped counts, the model should pass **`hierarchyNodeId`** when Host Context already provides a page-selected node id, **`hierarchyNodeName`** when the user typed a region/site label, or explicit **`intersectThingNames`** when a previous step already produced the exact bounded Thing list. Cross-check **[`CONTRACTS/API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)**, **[`docs/architecture/host-context.md`](../../docs/architecture/host-context.md)**, **[`docs/architecture/host-context-turn-state.md`](../../docs/architecture/host-context-turn-state.md)**, and **[`docs/architecture/host-context-generic-fallback.md`](../../docs/architecture/host-context-generic-fallback.md)** in this repository.



## Next

Chapter **10** applies the same **scope + taxonomy** ideas to **built-in alert** tools (**`query_alert_summary`**, **`query_alert_history`**) before **chapter 11** (built-ins → skill), **chapter 12** (skill → playbook), and **chapter 13** (`extended_tools.json`).
