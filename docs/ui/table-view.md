# Chat table display (UI)

**Use case**

A common exchange: the user asks "How many Stacking Robot assets are in the system?" The assistant answers
"There are 8. Would you like me to list their names, display names and serial numbers?" The user replies
"Yes, list their name, display name and SN."

**Expected result**

After the follow-up, the list is shown in the chat bubble as a very compact table next to the assistant's
text: three columns headed name, display name and SN, with thin borders so it takes little space. The rows
come from the tool result, not from text written by the model.

**How it works:** [`table-view-solution.md`](table-view-solution.md) covers where the table is decided (the
agent, after a tabular tool succeeds), how it is sent (`type: "table"` frame), how it is rendered
(`parler-ui`), how model output is checked, CSV export of large tables, and history replay.
