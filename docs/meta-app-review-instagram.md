# Meta App Review - Instagram Login

## Request being resubmitted

Request `instagram_business_basic` together with `instagram_business_manage_messages` for the
Instagram Login flow. This is a user-authorized OAuth flow, not a server-to-server or system-user
token integration.

The app is an AI assistant for a business's customer conversations. A business operator connects
only their own Instagram professional account from the tenant dashboard. After authorization, the
app reads the authorized account's ID and username, stores the connection for that tenant, receives
that account's Instagram DMs, and sends replies only to the customer who initiated the conversation.

## Exact use of `instagram_business_basic`

The permission is requested when the operator selects **Connect Instagram** in **Settings >
Channels**. After Meta redirects back from Instagram Login, the backend calls the authenticated
account endpoint with `fields=user_id,username`.

* `user_id` identifies the Instagram professional account being connected. It is stored as the
  tenant's Instagram channel binding and is used to route that account's incoming webhook events to
  the correct tenant.
* `username` is shown in the dashboard as `@username`, so the operator can confirm which account
  they connected.

The app does not use this permission to retrieve posts, followers, media, or data from accounts
that have not been connected by their operator.

## Recording preparation

1. Use an Instagram Business or Creator test account that is an accepted Instagram Tester for the
   Meta app. Use a separate test account to send the DM.
2. Make the app UI English in **Settings > Language** before recording. Keep browser and Instagram
   UI in English too.
3. Start with the target business account disconnected. Use a tenant whose dashboard user can log
   in normally.
4. Ensure the production or review environment uses the registered HTTPS OAuth redirect URI and the
   Instagram `messages` webhook is active.
5. Do not show passwords, access tokens, API secrets, JWTs, or customer personal data. Use only
   synthetic test names and messages.
6. Enable captions. Add brief on-screen callouts for each numbered step below; do not rely only on
   narration. Record at a readable resolution and do not cut between the authorization and result.

For a one-account preview, Meta's Self Messaging feature can initialize the conversation: send a DM
from the connected professional account to itself in Instagram. The `is_self` webhook registers the
self IGSID in the dashboard without an automated reply or a 24-hour response-window restriction.
The operator can then send the review message from the dashboard and show it in the same native chat.

## Screencast script

Target duration: about 3 minutes. The reviewer must see one continuous end-to-end flow.

1. Show the authenticated tenant dashboard in English and open **Settings**. Caption: "A business
   operator connects only its own Instagram professional account to let the assistant answer its
   customer DMs."
2. In **Channels**, point out the disconnected Instagram row and the **Connect Instagram** button.
   Explain that this button begins a user-authorized connection; it does not use a system-user
   token.
3. Click **Connect Instagram**. Keep the full Meta/Instagram authorization popup visible. Caption:
   "The operator is redirected to Instagram Login and explicitly grants access."
4. Complete the Instagram Login flow with the test professional account. Show the account-selection
   and permission/consent screen, including the requested permissions if Meta presents them. Click
   the approval button. Do not edit, crop, or skip the consent step.
5. Let the browser return to the dashboard. Show the success notification and the Instagram channel
   row now marked **Connected** with the test account displayed as `@username`. Caption: "With
   instagram_business_basic, TheBotsLab reads the authorized account ID to bind and route this
   channel, and its username to show the operator which account was connected."
6. From a separate logged-in Instagram test account, send a simple DM to the connected business
   account, for example: "Hello, what are your opening hours?" Show both sender and recipient so
   the reviewer can see the message was sent to the account authorized in the previous step.
7. Show the reply arriving in that same Instagram conversation. Caption: "The assistant replies to
   this customer DM using the connected account. Replies are sent only in response to inbound
   customer messages."
8. Return to the tenant dashboard, open **Conversations**, and show the same test conversation and
   its inbound message plus assistant reply. Caption: "The dashboard gives the business access to
   its own connected-channel conversation history."

The dashboard's **Channel** column should identify this conversation as `INSTAGRAM`; keep it visible
alongside the Instagram DM and reply as corroborating end-to-end evidence.

## Submission notes

Paste and adapt this for the `instagram_business_basic` request:

> TheBotsLab lets a business connect its own Instagram professional account and use an AI assistant
> to respond to that account's inbound Instagram DMs. The business operator initiates Instagram
> Login from Settings > Channels and explicitly grants access to their own account.
>
> We use `instagram_business_basic` after the OAuth callback to read only the authorized account's
> `user_id` and `username`. The `user_id` is stored as the tenant's Instagram channel binding and
> routes inbound Instagram webhook events to that business. The `username` is displayed in the
> dashboard so the operator can verify the connected account. We do not use this permission to
> access posts, media, followers, or unconnected accounts.
>
> The attached video shows the complete Instagram Login and consent flow, the account connected in
> the dashboard, a DM sent to that same account from a separate test user, the assistant reply in
> Instagram, and the matching conversation in the dashboard. This is a frontend OAuth flow, not a
> server-to-server or system-user-token integration.

For `instagram_business_manage_messages`, use the same video and state that the permission is used
to receive the connected account's inbound DMs through the subscribed `messages` webhook and send
replies in those customer conversations.

## Reviewer access checklist

* Provide working English-language test credentials for the tenant dashboard in the App Review
  instructions field.
* Ensure the supplied Instagram professional test account is authorized for the app and is not
  already connected if the reviewer must repeat the flow.
* State the exact dashboard URL, the test account username, and the path: **Settings > Channels >
  Connect Instagram**.
* If reviewer credentials cannot complete Meta consent independently, say so explicitly and make
  the recording self-contained rather than claiming the reviewer can reproduce it.
