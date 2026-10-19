# Test IT plugin for Jira Server / Data Center

The Test IT plugin integrates the server (Data Center) edition of Jira with Test IT and extends Jira for test management:

- Create test cases in the Test IT library from Jira. After a test case is created, a link to the Jira issue is added automatically under **Links** in Test IT.
- Link a Jira issue to:
  - Test cases and test results
  - Test plans
- Optionally **duplicate** Test IT work items into Jira **TestCase** issues in the background (per project mapping and schedule).

**Compatibility**

| Jira                    | Plugin artifact           |
|-------------------------|---------------------------|
| Data Center **8.x–9.x** | `testit-1.2.0.jar`        |
| Data Center **11.x**    | `testit-1.2.0-jira11.jar` |

Use the JAR that matches your Jira major line. One file does **not** cover both 8–9 and 11. Jira Cloud is not supported.

## Install the Test IT plugin

1. Download the installable `.jar` for your Jira version from the **Github releases**.
2. Sign in to Jira as an administrator and open **Applications**.
3. Open app management and upload the `.jar`: **Manage apps** → **Upload app**.
4. Select the file and click **Upload**. The plugin is installed into Jira.

## Configure the Test IT plugin

> **Project access depends on the API token**  
> The plugin can reach only those Test IT projects that the user who created the private token can access.

1. Open the plugin configuration page (**Configure** for the Test IT app, or `/plugins/servlet/testit/configuration`).
2. Fill in:
   - **Test IT URL** — base URL of your Test IT instance
   - **Private Token** — Test IT private API token
   - **Projects** — Jira project keys to integrate with Test IT (comma-separated, for example `PLUG,DOUB`)
3. Optional: configure **Work item duplication** (see below) and **TMS HTTP retries** (off by default).
4. Click **Save**.

The plugin is installed and configured.

Panels and the **Create TestCase** action appear only on issues in the configured Jira projects.

## Create test cases with the Test IT plugin

After installation and configuration, you can create Test IT test cases from Jira with automatic linking to the current issue.

1. Open an issue in Jira (in a configured project).
2. Click **Create TestCase** in the operations area under the issue heading (near **Add comment**).
3. In the dialog, select the Test IT project where the test case will be created. You can search by project name or by **Global ID** (digits only; enter the full Global ID).
4. Click **Create**.

A new empty test case is created in Test IT. A link to the Jira issue is added under **Links** on the test case. The test case appears on the issue page in the **Test IT testcases & testresults** panel.

## Link a Test IT test case to a Jira issue

You can link an existing test case to a Jira issue. It then appears in **Test IT testcases & testresults** on the issue page.

The plugin shows a work item on the issue **only when an Issue link** to that Jira issue exists in Test IT (under **Links**, link type **Issue**). A name prefix such as `TASK-57: …` is required.

### Link from Test IT

1. In Test IT, open the test case (work item).
2. In the left navigation, open **Links**.
3. Set the link type to **Issue** (or the equivalent for a Jira task).
4. Paste the URL of your Jira issue into the URL field.
5. Add the prefix to the work item name with the issue key and a colon (for example `TASK-57: Log in to the System`).
6. Click **Add**, then **Save**.

The test case appears in the **Test IT testcases & testresults** table on the Jira issue. If the test case is used in a test plan, related runs and result history can be shown under the work item (expand with the chevron). **Automated test results are not shown.**

### Unlink from Jira

On the issue page, hover the work item row and click **×**. The plugin removes the Issue link and the `{ISSUE-KEY}:` name prefix in Test IT.

## Link a Test IT test plan to a Jira issue

You can link a test plan to a Jira issue. The plan and summary information, including test status counts, appear in the **Test IT testplans** panel.

1. In Test IT, copy the test plan URL **including** the `/tests` path segment.  
   Example: `https://example.testit.software/projects/1567/test-plans/63749/tests`
2. On the Jira issue, in **Test IT testplans**, paste the URL into **Add test plan** and click **Add**. Plan data appears in the table.
3. Optional: click **Refresh test plans** to reload plan data and analytics from Test IT.
4. Optional: to unlink, hover the plan row and click **×**.

## Issue panels overview

### Test IT testcases & testresults

- Lists work items linked to the issue via Issue links in Test IT.
- Each row links to the work item in Test IT.
- Expand a row to see result history (date, test plan, status).
- Unlink with **×** on hover.

### Test IT testplans

- Lists test plans linked by URL on this issue.
- Typical columns: ID, name (link), version, product, start, end, status, and **Tests** (total and per-status counts from the project workflow, including custom statuses).
- Add, refresh, and unlink as described above.

## Work item duplication

Duplication creates and keeps Jira **TestCase** issues in sync with work items from a chosen Test IT project. It runs in the background on a schedule you define per mapping.

### Purpose

- If a work item in TMS has **no** Issue link to Jira, the plugin creates a TestCase in the mapped Jira project, writes a **Description**, adds an Issue link in TMS, and prefixes the work item name with `{ISSUE-KEY}:`.
- If a work item **already** has an Issue link, the plugin does not create another issue. It may add the missing name prefix and update the Jira Description when the work item content changes.

Work item types included in the duplication: **TestCases**, **SharedSteps**, and **CheckLists**.

### Set up a mapping

1. Open the plugin configuration page.
2. Under **Work item duplication**, click **Add mapping**.
3. Select:
   - **Jira project** — where TestCase issues will be created
   - **Test IT project** — source of work items (list loads from Test IT using your URL and token)
4. Choose a schedule:
   - **Every N minutes** — interval sync
   - **Daily at time** — once per day at the given time (server JVM timezone)
5. Turn on **Enable** for this mapping.
6. Click **Save**. Enabled mappings start syncing in the background shortly after save.

Each Jira project ↔ Test IT project pair must be unique. You can add several mappings for different pairs.

### What you see after Save

Each mapping shows status counters such as **created**, **updated**, **already linked**, and **errors** (refreshed on the configuration page).
