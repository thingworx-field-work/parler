# First run: conversation, gateway, and smoke prompt

## Goal

Set up a `conversationId` and prove **AlwaysOn** streaming from the mashup to your **`AIAgent`** before changing taxonomies or tools.



## Steps

### Open the `Parler` mashup in view mode

<img src="./__images__//image-20260530211209832.png" alt="image-20260530211209832" style="zoom:50%;" />



### Make sure your mashup is in the correct initial state

So far, you only have an `AIAgent` Thing, and it shows in the `Agent` dropdown list. The `ConversationId` dropdown list is empty.

<img src="./__images__//image-20260530211303776.png" alt="image-20260530211303776" style="zoom:50%;" />

For widget **0.1.92**, review these Composer inputs before the smoke run:

| Property | Course default | Purpose |
| --- | --- | --- |
| `ThemeMode` | `mashup` | use the active Mashup Style Theme; choose `parler-dark` only for the bundled standalone preset |
| `ProgressPresentation` | `detailed` | choose `compact` to replace routine activity detail with `ProgressLabel` while preserving rate-control and attention states |
| `ProgressLabel` | `Thinking...` | localizable compact working label |
| `RateControlLabel` | `Waiting for capacity...` | localizable compact provider-wait label |
| `EmptyStateMedia` | empty | optional ThingWorx Media Entity image for the empty conversation |

Missing or invalid enum-like values fall back to the documented defaults. These properties are binding targets;
they do not create a second theme engine or alter the AlwaysOn protocol.



### Create a conversation ID and use it for the entire training

You can give it a name and click the `Add new ID` button. It will create a conversation ID for you and it will show up in the `ConversationId` dropdown list immediately.

<img src="./__images__//image-20260530211504828.png" alt="image-20260530211504828" style="zoom:50%;" />



<img src="./__images__//image-20260530211520194.png" alt="image-20260530211520194" style="zoom:50%;" />



### Connect and check your connection status

Now, you can click the `Connect` button. You should be able to see the green connection text above the input box.

The status should include the loaded agent and widget versions. For example:

```text
Transport: connected, <agentVersion>:<widgetVersion>
```

If it only shows `?:<widget-version>`, the widget is connected but the `GetConnectionInfo` handshake did not return the agent
version. Check that the Gateway service permission includes `GetConnectionInfo`.

<img src="./__images__//image-20260607222123885.png" alt="image-20260607222123885" style="zoom:33%;" />



In `Transport: connected, <agentVersion>:<widgetVersion>`, the first number is the Parler agent extension version and the
second is the UI widget version. Your exact patch versions may differ; use the pair displayed in your own mashup when reporting issues.

<img src="./__images__//image-20260607222209342.png" alt="image-20260607222209342" style="zoom:50%;" />



### Enter your first prompt and send it out

You can try your first prompt: `who are you`.

<img src="./__images__//image-20260607222411995.png" alt="image-20260607222411995" style="zoom:50%;" />



**`tips`**: You can use arrow up/down to navigate the chat message history.

### Check the response

After you click the `Send` button, the UI will show a progress indicator.

<img src="./__images__//image-20260530211754624.png" alt="image-20260530211754624" style="zoom:50%;" />



Once the LLM responds to the agent, the agent will push the message to the UI. The original progress message block will disappear and only the final response will be kept on this UI.

<img src="./__images__//image-20260530211823074.png" alt="image-20260530211823074" style="zoom:50%;" />



**`Caution`**: For every screenshot in the training course, the response to the same prompt will likely differ in wording. You have to make sure the meaning is the same.

<img src="./__images__//image-20260530215140987.png" alt="image-20260530215140987" style="zoom:50%;" />



### Icons under each final response

<img src="./__images__//image-20260530211944990.png" alt="image-20260530211944990" style="zoom:50%;" />

* `Copy`: it will copy the final response text into the clipboard, and then you can paste it somewhere you like. Please keep in mind: it will only copy the final response text (in rendered markdown format), but it will not copy any local table or chart.
* `print`: it will open another browser page and show all content (including table and chart) in a plain HTML page, then pop up the browser print window for you to print.
* `thumb up`: You can click multiple times to switch from down to up or vice versa. Only the last one will win the UI display.
* `thumb down`: You can click multiple times to switch from up to down or vice versa. Only the last one will win the UI display.
* `info`: it will display information about the current LLM turn, including how many tokens have been used for input/output/caching in the current turn.
* `cut-off`: it will soft-delete all information up to and including the current final response. You will no longer see it in the UI, and any content before this time will not be included in the future context.
