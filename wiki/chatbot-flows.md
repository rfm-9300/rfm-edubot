---
updated: 2026-11-18
---

# Chatbot flows: the fixed, no-AI bot

Fixed bots that answer customers before the AI: a keyword starts a flow that offers options, asks
questions, checks opening hours, creates clients and bookings and hands over to a person, with no model
involved unless the company switches one small use on. The Chatbot page draws each flow on a board, its
steps as cards joined by lines. `docs/architecture.md` → "Chat flows" has the routing, the endpoints and
the diagram; `design-system/patterns.md` → "Chatbot" has the screen. Before
this, the only fixed-bot feature was the Instagram menus ([ai-persona-and-bot.md](ai-persona-and-bot.md)
has that history); the same `flows/` engine grew from them.

## Why it exists, and why apart from Persona

Rodrigo's question: much of a chatbot needs no AI (trigger words, specific replies), the Persona page was
built for an AI assistant, and a company should be able to have a fixed-style bot too. He chose a separate
page, everything that was missing built at once, and one PR. The Persona page keeps what belongs to the AI
(who it is, what it knows, what it may do) and the per-channel AI switch and start words; the **Chatbot**
page is the fixed flows. A flow answers first, the AI answers what no flow does, and where the AI is off
the team does.

- **No module of its own.** The Chatbot page and its API (`/app/api/chatbot`) ride on the `persona`
  module: no backoffice selection, no migration, and a company that sees the bot's settings sees its
  flows. The web nav adds `chatbot` whenever `persona` is on (like Finances for invoices and
  payments), and `bootAuthed` treats it as a page the session may open.
- **One document, two writers.** Flows, the AI switch and the start gates share one `chat_flows`
  document, so each page saves only its part (`saveFlows`, `saveChannels`). `PUT /persona/menus` is gone:
  an older page open in a tab gets a 404 on save instead of wiping new fields it can't represent.

## What a flow can do

- **Options, tapped or typed.** A message's options go out as taps where the channel has them
  (Instagram; WhatsApp reply buttons for up to 3, or a list of up to 10 when the step names the list's
  button) and as numbered lines where it doesn't (the website, or too many or too long). The author can
  choose "buttons, with numbers as a backup" (Instagram also gets the lines), "always numbers" or "my own text"
  (nothing is added, the message says what to reply). Whatever the
  display, a customer can type the number, the title or any extra word the author gave the option.
- **Choices that are kept.** A message with options can name a key ("Keep the choice as"): whichever way the
  customer chooses (tap, number, title, extra word, or the AI reading the reply), that option's *title* is kept
  under it, like a question's answer, for later messages (`{business_type}`), the client's notes and the team's
  notice. This is how a campaign flow records "area", "pain point" and "interest" without asking in words.
- **Questions.** A question keeps its answer under a key (`name`, `phone`, `email`, `date`, `time`,
  `service`, `note` or the company's own) after checking it is the kind of answer expected: a name, a
  phone, an email, a number, a date, a time, or yes or no, read in Portuguese, English and Spanish with
  fixed rules, no AI. Later messages insert answers as `{name}`.
- **Conditions.** Are we open now (weekly windows in the company's time zone), is this already a client,
  is an earlier answer a given value.
- **Actions.** Create a client, create a pending booking, tell the team (a `chatbot_answers` notification
  linked to the conversation), each with a "if it fails" step; a failing action with nowhere to go hands the
  chat to a person. Hand over to a person is a step of its own.
- **Not understood.** A step asks again as many times as the flow says (once, for a new flow), then the
  flow hands the chat to the team (saying the author's own words) or leaves the message to the AI, or to the
  team where the AI is off. With "let the AI read answers the flow can't" on, the AI is asked first, only for
  what a reply means.

## Decisions and why

- **A board instead of a list (2026-11-18).** The page first listed a flow's steps as long forms, one under the
  other (folding them, the same day, only shortened the list). Rodrigo asked for something more interactive, "a
  kind of whiteboard where we can better see all the flow". Each flow is now a board: a start card (its keywords)
  and a card per step, each output a dot on the card's edge with a line to where it leads, and the step's fields
  open in a side panel beside it, whose other tab is the test chat. What it does and why:
  - **Every edit still has a field.** Linking by dragging a dot, adding a step where a line is dropped, moving
    cards and unlinking a line are shortcuts; the side panel's selects ("Leads to", "Then go to", with "New
    step…") do the same, so keyboards and screen readers lose nothing. Folding went: a card is already short.
  - **Places are saved, but only once someone arranges a flow.** A step's `at` and a flow's `startAt`
    (`BoardPoint`, sent as `x`/`y` and `startX`/`startY`) are optional. A flow with none (every flow made before,
    and flows written through the API) is laid out by the board each time (`FlowBoard.layout`: columns by the
    longest way from the start, lines that go back ignored, each card level with what leads to it, cards nothing
    reaches in a band underneath), so it stays tidy as steps are added. The first card moved writes every card's
    place into the draft, so the arrangement is saved whole; Tidy up drops them all again. Moving cards is an
    edit like any other: unsaved, undoable, saved by Save.
  - **The board is its own module.** `chatbot-board.js` (`window.FlowBoard`) knows nothing about flows: it
    measures the cards `chatbot.js` draws, places them, draws the lines in one svg, and turns pointer and wheel
    gestures into callbacks (select, move, connect, drop on the empty board). `chatbot.js` keeps the flows, the
    side panel, the test chat and saving, and redraws the page after any change except a move or typing.
  - **Undo and redo** keep copies of the draft (and which flow and step were open), one per change, one per
    field typed in, up to 100. Discard clears them.
  - **The test chat lights the board.** Each test turn returns its `flowId` and `path`, the steps it went
    through (`FlowResult.path`, silent conditions and actions included, which messages alone can't show). The
    page lights the lines between consecutive steps (all of them when two options of one step lead to the same
    place: it can't tell which was typed), shows that flow, rings the card where the customer waits and pans to it.
  - **Flagged before saving.** Cards and tabs mark what the server would refuse (no text, an option leading
    nowhere, a question without a name, no keywords…), from the page's own reading of `ChatFlow.problems()`;
    the server still checks on save, and a refused save opens the step it names.
  - **Wheel and height.** The board takes the scroll wheel (pan; Ctrl or a pinch zooms), so the page is sized to
    the screen like Conversations' and doesn't scroll under it. Phones get the board full width; a card tapped
    opens the side panel over it.

- **The AI never writes to the customer inside a flow.** Rodrigo asked for a hybrid: when we can't read an
  answer, an AI model says only how to interpret it and what to do. `AiFlowInterpreter` sends the question,
  the options, what is expected, today and the reply, and takes one small JSON back
  (`ACCEPT`/`REPEAT`/`HAND_OVER`, an option number, a value). Its answer is validated (an option must exist,
  a value must pass the same reading as a typed one), so a bad answer asks again at worst; the reply is
  told to be data, not instructions; no customer or company data goes with it. It is off by default per
  flow, runs only after the usual reading failed and while the company has tokens, and its use is metered as
  `chatbot`. Every message a customer sees is one the company wrote.
- **No server-side prose.** The engine can't say "I didn't understand" because it doesn't know the
  language: a retry is the author's `retryText` (else the question or options are simply sent again) and a
  hand-over's apology is the author's `handoverText`. The only server text that reaches a person is the
  hand-over reason, built from the flow's name and the answers.
- **Sessions in Mongo, the engine stateless.** `FlowEngine` takes the session in and returns the next one,
  which makes it testable without a database; the pipeline reads it before the rate limiter and stores it
  before sending. A session idles out after 12 hours.
- **A tap's payload carries the option's position** (`flow:<flow>:<step>:<n>`): WhatsApp refuses a message
  whose buttons or list rows share an id, and two options can lead to the same step.
- **Actions use the real repositories** (clients, the booking scheduler, notifications) and need their
  module: the Chatbot page warns when a step's action can't work for the company. Bookings come through the
  customer's channel as their source, so the company's booking hours apply.
- **The Persona test chat no longer runs flows.** Trying them has their own test chat with a channel to
  pick (taps versus numbered lines), a pretended time (for opening hours), a known-client switch and notes
  for what each action would do, over a dry-run that writes nothing.

## Gotchas

- **Existing menus keep today's behaviour.** A flow stored before `retries` and `afterMisses` existed reads
  as `retries = 0` and `LET_AI`, so a free-text reply to a menu still goes straight to the AI (or waits for
  the team) instead of re-sending the menu and then handing over, which is what a *new* flow does. The
  defaults differ on purpose: the constructor's (new flows) and the repository's (documents without the
  field). A customer who types a menu's number or an option's title now chooses it, which is new.
- **Save the session before sending.** A channel that refuses a message (a bad token, a closed 24-hour
  window) throws out of `deliver`; the first version saved the session after sending, so a refused menu left
  the customer starting over. Found by running the e2e with a fake token. `ChatbotPipelineTest` covers it.
- **A keyword inside an answer is the answer.** At a question, flow keywords never restart a flow ("my name
  is Menu Silva"); at a waiting message with options they do ("menu" works anywhere).
- **`FlowStep`'s positional order is `id, text, buttons`** and `kind` comes after, so tests and the
  repository written for menus keep compiling. A step stored before kinds existed reads as a message.
- **Option titles are at most 20 characters**, for every channel, because Instagram quick replies and WhatsApp
  buttons stop there. Put the full wording in the message text; the title is also what a kept choice stores.
- **A tap keeps its choice only from the message the customer is at.** `keepTap` checks the session's step has
  that option leading where the tap leads, so an old message's button can't overwrite a later answer.
- **Messages a flow sends in one turn are stored a millisecond apart.** They would otherwise share a timestamp
  and the inbox could list them in any order (seen with a reply followed by the next question).
- **A flow whose silent steps lead back to each other** is refused (`loop`): they would send messages
  until the 12-step cap each time.
- **Instagram in a browser shows no quick-reply buttons** (reported by Rodrigo, 2026-11-18), while the Instagram
  app does. "Buttons, with numbers as a backup" (`TAPS_AND_NUMBERS`) is for that: Instagram gets the buttons and
  the numbered lines under the text, WhatsApp only its buttons (`ChannelCapabilities.quickRepliesMayBeHidden`),
  so one flow can serve both. "Always numbers" (`NUMBERED`) also covers the browser but takes the buttons from
  the app too, and is per step, so it takes WhatsApp's buttons as well. The lines are dropped, keeping the
  buttons, when text and lines together pass Instagram's 1000 bytes.
- **WhatsApp lists need a label.** A list's button has a name the author gives in their language; without
  one, more than 3 options go out as numbered lines instead.
- **Delivery failures show in the numbers**, not as errors: the flow's head on the Chatbot page counts
  messages the channel refused apart from the ones it sent.
- **Local runs read `.env` from the working directory**, which overrides the environment: to point the
  app at a scratch Mongo, run the built jar from a directory without one.
- **The server leaves defaults out of the flows it sends** (kotlinx `encodeDefaults` is off), so a flow with
  `retries = 1` came without `retries`, and the page showed "Don't ask again" for it until it was saved. The page
  now fills the left-out flow fields (`FLOW_DEFAULTS` in `chatbot.js`); a new field with a default needs adding there.
- **Board gestures (2026-11-18):** capturing the pointer on press sends the next `click` to the capturing canvas, not
  to the card or line clicked, so every click on a line read as a click on the empty board; the board captures only
  once a press becomes a drag. The board is `overflow: clip`, not `hidden`: a hidden box still scrolls when a card in
  it is focused or scrolled into view, and the world would slide away from where `FlowBoard` thinks it is. A click
  that redraws the page reaches the old board on its way up, so `FlowBoard` ignores events once its canvas left the
  page (a menu item emptied by the redraw read as a click on the empty board and closed the step just added).
- **A scrolling grid of fixed height squeezes a box that clips.** Auto rows may shrink an `overflow: hidden` item to
  nothing when the grid is too short for its content: the side panel's options list vanished. `.chatbot-side__scroll`
  sets `grid-auto-rows: max-content`.

## Checking it

- Unit tests: `FlowEngineTest` (the engine against a fake world, the path of a turn), `FlowAnswersTest`, `FlowRenderTest`,
  `AiFlowInterpreterTest`, `ChatFlowsTest`; with Mongo: `ChatFlowRepositoryTest` (places on the board too), `ChatbotPipelineTest`
  (the real pipeline factory: taps and numbered lines, sessions, clients, notices, hand-overs, a refusing
  channel, the AI reading), `ChatbotRoutesTest`; `WhatsAppInteractiveTest` for the Graph payloads.
- Browser: `scripts/chatbot-e2e/walkthrough.mjs` builds a flow on the board by hand (cards opened beside it,
  dots dragged onto cards, a card moved with undo and redo, a line dropped on the empty board, a line unlinked),
  tries it unsaved while the board lights the way, saves it with its places, checks a refused save and Tidy up,
  and runs a richer one with signed WhatsApp webhooks; a phone and a member's read-only board close it.
  `scripts/persona-e2e/walkthrough.mjs` covers Persona → Channels. Commands are in the repo `CLAUDE.md`.
- Not exercised against Meta: the interactive buttons and lists are checked against the documented
  payloads and the recorded request bodies, not a live WhatsApp number.
