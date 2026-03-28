# Bill Reminder App Specification

A simple and efficient way to manage your bills and never miss a payment.

### Key Features
* **Google Authentication**: Secure sign-in using Google Play Services.
* **Gmail Integration**: Automatically fetch and parse bill-related emails to simplify tracking.
* **Calendar Sync**: Add bill due dates to your Google Calendar as events.
* **Local Storage**: All bill data is stored securely on-device using Room database.
* **Manual Entry**: Add and edit custom bills manually if they are not in your email.
* **Reminders**: Receive timely notifications before bills are due.
* **Status Tracking**: Mark bills as paid or pending with a clear visual interface.

### Constraints & Design Requirements
* **Due Date Parsing**: Fetch the due date from the email content based on the actual payment deadline.
* **Filtering**: Provide filters for Due, Paid, and Overdue bills using a modern interface (e.g., Filter Chips or Bottom Navigation).
* **Retention Policy**: Automatically exclude/hide bills that have been overdue for more than 15 days.
* **Bill Card Design**:
    * **Information**: Service Provider, Amount, and Due Date. (No mail subject line).
    * **CTAs**: "Add to Calendar", "Set Reminder", and "Mark Done".
* **Gmail Integration**: Include a feature to directly open the source email in the Gmail app from the bill card.

### Utilize Gemini LLM features:
* Use gemini api key to use LLM capabilities to filter out not really invoice email or payment due emails.
* There are certain false negative emails which are just notices or informational and somtimes even duplicate.
* Call gemini api judiciously to check which emails are truly invoice due and need a payment. Skip duplicates or the ones which do not really need payments.
* Keep a cache or storage of the ones that you have already checked with Gemini so that no unnecessary multiple calls are made to Gemini API.
* Cost is a concern there.

### Issues reported:
* Email Preview is not correct, it is just showing email as a text, also not showing the full content. Investigate the reason for it and fix. The fix is not just the full email text but also well presented ui preview like a mail should look.