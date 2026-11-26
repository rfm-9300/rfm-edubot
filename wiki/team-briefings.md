---
updated: 2026-11-24
---

# Team briefings

A company messaging its own people every day on WhatsApp, each with their own day: their shifts, the
Serviços rows they do today, or the site issues assigned to them. It is the Agents module, not a
parallel system: a briefing is an agent whose schedule runs once per employee (`forEach: employee`),
and the Briefings page (`/app` → Briefings, `app/briefings.js`) shows its runs by day and person.
Engine background: [agents-automations.md](agents-automations.md).

## How a briefing is built

- **Who.** `EmployeeAudience`: active employees whose phone has a digit, minus anyone tagged
  **`no-briefing`** (any case; an ordinary [record tag](record-tags.md), so the opt-out needs no field
  on the employee). `SubjectQueries.employeesToBrief` puts the opt-out in the query and sorts by id
  before its 300 limit, so a big team doesn't lose a different set of people each day.
- **What they get.** `EmployeeBriefing` builds an employee run's variables: `employee.*` (with `tags`
  as a list, so `employee.tags contains equipa-a` picks a team) and one group per module, each a count
  and **one line** of text: `shifts` (planned shifts today, excused ones left out; time clock),
  `services` (today's Serviços rows assigned to them, with client and address; Services) and `issues`
  (open site issues assigned to them, overdue first; Building). A template parameter can't hold a line
  break or a tab and takes at most 1024 characters, so names are flattened and a long day ends in
  "…and N more" after whole items. A group whose module the company lacks is empty, isn't offered in
  the agent editor, and makes an agent that reads it fail validation (`needs_module`), so the schedule
  itself only needs Employees. Bookings have no employee link and tasks can't be assigned to employees,
  so neither is a group.
- **How it goes.** `whatsapp.send` with `to: employee` (the validator allows it only on an employee's
  run, and only it or a typed number there). They never wrote to the company, so outside the 24-hour
  window only an approved template goes; with no template picked the step falls back to a task.
- **Presets** (Agents gallery, Team category): shift, services and open-issues briefings. Each sends
  daily at a time (optionally weekdays only) with a guard `<group>.count > 0`, so someone with nothing
  that day gets nothing, and starts on "Ask me first" like every message preset (`AgentTemplatesTest`).
  They set `businessDaysOnly = false`: the company's "business days only" would hold Saturday's run to
  Monday, where it would be rebuilt with Monday's day beside Monday's own message.

## Decisions from the review (2026-11-24)

- **An employee run's approval expires at the end of its local day** (`AgentRunExecutor.approvalExpiry`),
  not after the company's 3 days, and `approve` refuses one past its expiry even before the sweep marks
  it. An approval stores its input as rendered, so approved the next morning it would send yesterday's
  shifts as "today" beside the new day's own run.
- **A member of the team has a cap of their own** (`AgentMessaging.teamCapped`, 3 messages in 24 hours
  from any agent; over it the step is skipped, not held a day). The company's caps protect customers,
  and their weekly default of 5 would silently stop a daily briefing on day six; no cap at all let an
  hourly schedule message each person 24 times a day.
- **Briefings keep employees out of the customer inbox's automation.** `storeOutbound` still stores the
  message (the delivery ticks need it), but a chat it creates for a team member starts with the bot off
  (`Conversation.TEAM_MEMBER`, `bot:team`, its own inbox banner) and the new contact raises no
  `contact.created`, so lead agents don't greet staff. A chat that already existed keeps its setting.
  Not covered: agents on `message.received` still see an employee's reply.
- **The page never stores a preview.** Today's message for each person is built like the run builds it
  (context, conditions, guard, the step's own preview with the approved template) in memory, once per
  person per page load.

## The Briefings page and its API

`/app/api/briefings` (`agents/briefings/`), gated by Agents and Employees: the list with today's
counts, one briefing's day (`?day=`, today or earlier) with one row per member of the team, `send`
and `approve`. Sending is for those who manage agents; approving follows the approval's approvers.

- **Send now before the day's fire takes the schedule's own run** (`AgentScheduler.scheduleKey`), so
  the fire finds it and doesn't send twice; after the fire it is a run keyed to the day. Without
  `again` a person gets one a day; `again` is always a new run, under the team cap. Someone the
  briefing leaves out or whose day has nothing for it gets no run at all (the same in-memory check as
  the preview), so "going out: N" counts only messages that go.
- **A row's state** comes from the person's latest run that day and, for a sent message, the stored
  message's ticks (the status webhooks update it). Same-instant runs order by id.
- Person rows without a run today are previews: scheduled (the fire is later today and the briefing is
  on) or not sent; earlier days show "not sent".

## Before a company can use it

The company's WhatsApp account needs a payment method and an approved template with two body values;
the app can create templates only where its token has template permission (see
[whatsapp-and-meta.md](whatsapp-and-meta.md)). The employees' consent is the company's to confirm.
