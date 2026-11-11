# Hana Huang | Daily reporting (feature preview)

Hana Huang is BoaLi's third character and its evening reporter, a playful reference to Alice Huang. Boa continues tracking steps/water and Lyn continues recording GPS walks. Hana does not collect GPS trails or change the existing tracking pipeline.

## What's implemented
- Third **HANA · REPORT** tab with gold theme and an original typography-based character panel (portrait art not yet bundled).
- Stores local daily entries for wake-up time, meals/drinks, dental care, hair care and notes in private app preferences. Step count, step-estimated distance and logged water come from Boa's existing repository.
- Generates a clearly disclosed automated daily report and an invitation to Ali asking if he wants daily updates. His response is not silently read; the user must mark the consent checkbox only after receiving explicit permission. Changing phone numbers clears the consent flag.
- Prepares a **local snapshot around 8:00 PM Asia/Karachi** and posts an Android notification. Uses WorkManager: Android/HyperOS may defer it. Auto-generated reports are saved on device, even if notifications are disabled.
- Opens the appropriate WhatsApp message composer for the configured number. **The Android user must review and tap Send.** BoaLi does not silently dispatch WhatsApp messages.
- All personal activity data stays on the phone until the user initiates sharing. No backend API keys are embedded in the APK.

## What's required for truly unattended WhatsApp sending
WhatsApp Business Platform (Cloud API), an eligible sender/business setup, a secure server, Ali's prior consent to receive these updates, and Meta-approved initiated-message templates. These conditions cannot be met merely by granting Android permissions, and we should not add fragile Accessibility-based auto-clicking.

API design:
1. Ali gives explicit permission and can revoke it.
2. A server stores Meta credentials securely (never hard-code access tokens in an Android APK).
3. A daily scheduler requests the 8 PM snapshot from the app through an authenticated channel or receives an encrypted upload after the user opts in.
4. The server sends an approved template through Cloud API to the consented E.164 number, handles failures, duplicates, and opt-out.
5. A delivery status callback is surfaced in Hana's reporting history.

**One possible template to submit for Meta approval** (approval and category are not guaranteed):

```text
BoaLi | Hana Huang's automated daily report for {{1}}

Date: {{2}}
Walking distance (step-estimated): {{3}}
Steps: {{4}}
Meals and drinks: {{5}}
Dental care: {{6}}
Hair care: {{7}}
Water logged: {{8}}
Other notes: {{9}}

This is an automated report you agreed to receive. Reply STOP to opt out.
```

Values for the placeholders can change with each send without resubmitting the static approved template. If the fixed wording, placeholders or structure change, the template may need reapproval. Do not stuff unrelated content into a generic variable. The platform only permits free-form messages during its 24-hour service window; outside that window proactive messages must use approved templates.

Meta might reject or reclassify a **personal lifestyle diary** because the Business Platform is intended for business-initiated communications. Consent alone does not guarantee template approval. Verify eligibility, category, privacy, and pricing before enabling automatic delivery.

## Testing and data notes
- On-device validation needed for WorkManager timing under Redmi HyperOS battery-saving settings, notifications, and the WhatsApp app picker.
- The current report uses **step-estimated distance**. It does not silently substitute the last GPS route or sum multiple routes as a daily measurement.
- No personal contact number was configured in source code. Enter Ali's number through Hana's tab before requesting consent.
- Debug and release signing must remain consistent to preserve existing local tracking history.
