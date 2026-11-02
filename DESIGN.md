---
version: alpha
colors:
  primary: "#2563eb"
  surface: "#ffffff"
  text: "#0f1f3d"
  muted: "#3d5a80"
  error: "#b91c1c"
typography:
  body:
    fontFamily: 'system-ui, -apple-system, "Segoe UI", sans-serif'
    fontSize: "14px"
    lineHeight: "1.6"
rounded:
  control: "8px"
  composer: "14px"
spacing:
  gap: "8px"
components:
  historyStatus:
    textColor: "{colors.muted}"
  composer:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.composer}"
---

# CoWork Channel Web design

## Overview

This Chinese chat frontend follows CoWork's session-navigation and conversation model.
The left sidebar contains only sessions returned by the authenticated authorized catalog.
Clicking one selects it; the right pane contains its title, real execution state, messages and composer.
Connection credentials live in Connection settings. Personal authorization remains in CoWork settings.
Canonical behavior: ../channel design v2/web-chat-frontend.md and session-state-synchronization.md.

## Colors

Runtime ownership is model B: CSS custom properties in channel-app/src/main/resources/static/web.html
own the palette. Reuse CoWork's --bg0/--bg1/--bg2/--bg3, --t1/--t2, blue and border roles.
Use --action for filled actions, --error with text, and --warning for waiting. OS dark mode maps these
same roles to CoWork's dark palette. Forced colors retains native operability.

## Typography

Use the existing system font stack, 14px/1.6 body, 17px conversation title, 12px metadata.
Chinese text and long message content wrap. No remote fonts or decorative type.

## Layout

Desktop: 264px sidebar and flexible conversation pane, fixed header/composer with an independently
scrolling conversation. At 680px and below, stack a bounded session area over the chat.
Conversation width is bounded at 820px; no horizontal overflow at 390px.
Native dialog caps at 480px and 100%-32px. Reliable delivery is retained by the common L2 inbox;
page memory does not determine the receiver retention boundary.
Requested history pages accumulate through explicit pagination; the first selection loads one page.

## Elevation & Depth

Use CoWork's restrained borders and flat panels. Only connection settings use a modal backdrop.

## Shapes

8px native controls, 14px composer, 12px user message corners. Preserve semantic buttons and fields.

## Components

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
| --- | --- | --- | --- | --- |
| Select/Listbox | Native select#workspace in web.html | L2 workspace.list/select; workspace-session-navigation.md | OS popup and keyboard behavior | web.test.cjs navigation CAS, unknown result and reload tests |
| Form | Labeled native inputs and textarea in web.html | Draft and validation handlers in web.js | Connection / composer | web.test.cjs |
| Dialog | Native #connection-dialog | showModal/close; browser focus restoration | Connection settings | Keyboard/secret tests |
| Navigation | Native buttons in #session-list | Authorized session.list; aria-current selection | Desktop / stacked narrow | Catalog/switching tests |
| Scrollbar | Global root CSS and native scroll regions | Shared root scrollbar tokens | OS / forced colors | Narrow viewport test |
| Feedback | Header execution status, inline status, message receipt metadata | Actual snapshots and receipts | Waiting / ready / unknown / failed | State/race/lookup tests |
| Toast | renderNotifications in web.js, #task-notifications in web.html | v2.29 task-notifications-and-inbox.md | Completion / backlog summary / explicit source navigation | Completion, narrow and gap browser tests |

## Interaction contract

No session is implicitly selected. Clicking carries an explicit logical target for both state reads
and messages; it does not depend on another tab's current binding. Web adapter converts literal chat
text into existing input.submit packets; the browser never constructs trusted identity or Channel packets.
Slash text in the composer stays literal. Preserve whitespace, Enter sends, Shift+Enter inserts newline,
and IME confirmation never sends. Drafts and delayed replies belong to their original logical sessions.

L3 reads actual backend status, revision and observedAt. Observe selected targets immediately and every
10 seconds. Missing, failed or >30s old observations block ordinary sends with an actionable hint.
Input behavior follows the host's shared backend business rules: verified PAUSED/user_turn accepts
ordinary continuation on the original task; terminal tasks can open a new task on an available agent.
Explicit tool_result/no_resume interactions retain their dedicated response actions. A generic waiting
status cannot decide submit availability. Configuration and chat surfaces use the same host display
mapping. Canonical behavior: ../channel design v2/host-channel-input-consistency.md (v2.21, implemented and
verified in v2.23). Negotiated rawStatus/input facts drive display and availability; normal user_turn
continuation has no waiting warning. Generic task waiting cards do not expose internal reason codes. The current
profile now exposes optional history_read for FULL adapters through the instance-bound read port.
It still has no explicit HITL response API. Loading history does not change input availability.

History follows ../channel design v2/session-history.md (v2.22). On selection, load the newest page;
Load earlier messages prepends a page and retains scroll position and drafts. Reuse native buttons,
the existing conversation scroller and inline feedback. Deduplicate by stable messageRef, preserve
records on retry, and distinguish capability denial from empty data. Internal/backend identities and
credentials never appear in the public view; full-body capability does not replace user authorization.

Accepted custody, backend execution and final reply remain distinct. acceptedBy=agent confirms backend
admission even without taskRef; absence of an optional task ID cannot lock the composer indefinitely. Unknown writes only query their
original request; asynchronous rejection preserves drafts. No automatic resubmission or draft send
on state recovery. Revoked sessions disappear after catalog refresh; changing credentials clears old
views. Secrets stay masked/revealable and absent from URLs and browser persistent storage.

## Do's and Don'ts

Keep authorization in CoWork, visibility in L2, protocol conversion in L1 and backend access in L3.
Keep the selected title and execution state visible. Use native focus, live regions and text feedback.
Do not invent historical messages, infer rights from a title, misroute replies after switching,
implicitly approve tools or replace structured answers with ordinary text, or expose packet/debug
details in ordinary chat flows. Normal user_turn continuation remains ordinary chat input.


## v2.24 navigation contract

Canonical owner: L2 NavigationService/SubmitService; source of truth:
../channel design v2/workspace-session-navigation.md. Native select#workspace is the shared
workspace picker for this page; existing sidebar buttons select sessions. Web viewId isolates tabs.
The Web facade accepts ordinary text/selection parameters; L1 maps them to the existing IngressPort typed operations. UI only applies confirmed
server selection, never an optimistic local target. Workspace changes clear session selection;
same workspace preserves it. Pending switches disable sends and further switches and expose an
original-request lookup button. Reload retains the tab identity and unresolved request ID, never a
credential. Verification: navigation Java/Python regressions, Edge tests and separate real HTTP capture.

Unselected workspace and empty authorization are different states. When visible workspaces exist but
none is selected, show the workspace count and ask the user to choose a workspace; do not claim zero
authorized sessions or ask them to authorize again. Once selected, the session count explicitly refers
to the current workspace. Only a genuinely empty authorized catalog links back to CoWork configuration.
The stacked narrow layout reserves visible space for session buttons after the workspace picker and
search; navigation controls must not collapse the scrollable session list to zero height.

## v2.25 navigation read budget

Normal successful session selection adopts the committed result, then reads only target state and uncached history. Preserve the catalog, pagination, drafts and workspace picker. Workspace selection fetches the new session list only. Unknown/conflict recovery uses the shared selection.get operation to read current L2 selection, never trusting a stale replay. Initialization, manual refresh, independent 30s expiry and actual authorization/catalog invalidation refresh directories. Source: ../channel design v2/workspace-session-navigation.md, INV-72 and S199–S204.


## v2.26 explicit human interaction

Canonical owner: existing InteractionPrompt/Required/Respond and L2 interaction intake. Source: ../channel design v2/interaction-chain.md. Use native fieldset/legend, radio/checkbox and labelled text controls; render operation arguments as text, no HTML injection. Recommended options never imply consent. Preserve drafts by original target and interaction version; submit entire required groups. Pending or uncertain writes disable repeat decisions and query the original request, including reload. Terminal facts close old forms but do not prove our own uncertain write succeeded. Recovery returns the same EventPackets as live delivery; absence of the snapshot field is not an empty pending set. Authorization remains in CoWork/L2.

## v2.29 completion and inbox recovery

Canonical owner: L2 DurableInboxService for custody, cursors, grants and progress; Web L1 maps authenticated
read/ack requests. The page uses one #task-notifications live region and renderNotifications owner.
Reuse the existing palette, borders, native buttons and navigation flow. Notifications sit above the
measured composer, retain keyboard focus between polls, offer an explicit source-session action and a
close action, and summarize backlogs with access to all reminders. Arrival never changes selection.
Completion and answer are independent cards; original messageRef references reconcile late results.
An explicit retention gap offers resynchronization without implying the missing records were received.
This page does not maintain a durable local body cache, so it sends only explicit read acknowledgements;
reload replays the server inbox. The received API is available for clients that durably apply pages.
Credentials remain in memory. Identity changes discard old page views; server authorization remains
authoritative on every read, acknowledgement and navigation. Source: ../channel design v2/task-notifications-and-inbox.md.
