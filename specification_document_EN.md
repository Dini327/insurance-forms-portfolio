# Specification Document
# Digital Forms System for Insurance Agents
## 1. What the System Does
Today, the insurance agent fills out the health declaration form on behalf of the client during a phone call. This consumes expensive agent time, and when a medical issue comes up, additional forms have to be coordinated manually.
The system moves the filling to the insured person themselves. The agent sends a link, the insured fills it in on their own, and the system automatically identifies whether additional forms are required and handles them. At the end, the system fills in the insurance company's official form and sends it by email.
The goal: to reduce the agent's work to a single action — choose a company and click send.
The system will be used by the Client herself, and will also be sold to additional insurance agents on a monthly subscription model.
## 2. Who Uses the System
## 3. Agent Registration
The agent registers on their own, and enters at registration:
Full name, email address, ID number, agency name, mobile.
Agent number — an official and unique identifier
Personal logo, which will be displayed to their insured clients

The agent number is also used for identification for the purposes of the trial period, so that it will not be possible to register again with a different email address and receive an additional trial.
The logo will be displayed in the emails sent to insured clients and on the filling screen. The logo will not be stamped onto the insurance company's official PDF form, since the form is sent to the company in its original format.
## 4. The Main Process — Health Declaration
## 1. The agent enters the personal area, chooses an insurance company and insurance type (the available types differ per company — see §12), enters the insured's name and email and clicks send.
## 2. The insured receives an email with the agent's logo containing a personal link. A reply to the email will go directly to the agent.
## 3. The insured opens the link and sees on screen an interface resembling the official form. They fill in the questions.
## 4. On submission, the system checks the answers against the exclusion rules table.
## 5. If exclusions were identified — the system displays the required exclusion forms to the insured, and they fill those in as well.
## 6. The insured signs by drawing on the screen.
## 7. The system fills the official form's fields with the answers received, and sends it by email to the agent and the insured. The data is deleted.
## 5. Exclusion Forms
Each insurance company has an initial health declaration form, alongside approximately 25 exclusion forms that are required when a particular answer indicates a medical issue.
The exclusion rules will not be written inside the code. They will be stored in a separate data table that maps a particular answer to the forms required as a result.
The practical meaning: adding an insurance company or changing a rule is a data update and not writing code. This makes the system expandable at a significantly lower cost.
Defining the exclusion rules for each company is the Client's responsibility, and will be provided as a clear table.
## 6. Standalone Forms Repository
Some exclusions do not end with an additional form: the insured is required to furnish medical documents, and only after receiving them does the insurance company require certain forms to be filled in.
For this purpose the system will include a repository of standalone forms. The agent will be able to choose a particular form from the repository and send it to a specific insured person, separately from the main process.
The flow is identical: the insured receives a link, fills it in, signs, and the filled form is sent by email to the agent and the insured.
## 7. Signature
The insured signs by drawing on the screen using the mouse or finger. The system captures the drawing, converts it into an image and places it in the signature field on the form.
A certified digital signature under the Electronic Signature Law is not included in the system.
## 8. Subscription, Trial and Payment
Trial period
An agent who registers receives 4 days of full use free of charge, from the moment of registration.
At the end of the period, an attempt to use the system will display a message that the trial period has ended, and a request to enter a payment method in order to continue.
Monthly subscription
After entering the payment method, the agent is charged automatically every month.
On every monthly charge, a single email only will be sent to the agent, including the charge notice and the receipt as an attached file — and not two separate emails.
In the personal area the last four digits of the saved card will be displayed, for identification only.
Payment method security
Credit card details will not be stored in the system in any form. The card will be entered in the payment provider's secure form, and the system will store only an encrypted identifier (token) enabling future charging, alongside the last four digits.
The payment processing account will be opened in the Client's name and the funds will go to her directly.
## 9. Emails
The emails will be sent from a verified domain of the system, so that they do not arrive in the spam folder. The agent's address will be set as the reply address, so that a reply from the insured will go directly to them.
Types of emails in the system:
To the insured — a link to fill in a form, with the agent's logo
To the insured and the agent — the filled official form, at the end of the process
To the insured — a request to furnish medical documents, when required
To the agent — a monthly charge notice with the receipt attached
## 10. Privacy and Security
The founding principle: the system does not store medical information, at any stage.
The insured's answers are processed in memory only. The official form is created and sent by email, and the data is deleted. A copy is kept for the agent and the insured in their mailbox, exactly as happens today.
This design eliminates most of the regulatory complexity applying to medical information, and significantly reduces the exposure of both parties.
Additional security measures:
The link to the insured contains a long random identifier, valid for one request only and for a limited time
All traffic is encrypted (HTTPS)
Passwords are stored encrypted and cannot be recovered
Keys and secrets are stored in the server settings only, not in the code

What is stored in the database: agent accounts, logo, operational metadata of requests (to whom it was sent and when), subscription status and billing records. Without medical content.
## 11. Separation Between Agents
The system is sold to independent agents, some of whom compete with each other. Therefore the separation between them is a core requirement.
Every record in the system has an explicit association to the agent it belongs to
The filtering is enforced in one central place, and not in scattered checks
The agent's identity is always determined on the server side, and never according to what is sent from the browser
The delivery tests will include a deliberate attempt to access another agent's data, and verification that it is blocked

**Admin visibility boundary.** The Client herself is a practicing insurance agent, and therefore a competitor to the agents who purchase this system. An administrator who can see everything is not only a privacy problem but a product failure: no agent will buy a system if a competing agent can see their client list through it. The platform administrator (Admin) is therefore a distinct role from Agent, with deliberately restricted visibility — not full access with a smaller UI.

Admin is permitted: the list of agents, each agent's subscription and billing status, the number of submissions per agent (for billing purposes only), and management of insurance companies, forms, and exclusion rules.

Admin is forbidden: the identity of any insured person (name, email) and which form was sent to whom. That data remains visible only to the owning agent.

This boundary is enforced through the same central filtering layer used for agent-to-agent separation, not through scattered checks. In particular, usage counts shown to Admin are computed as aggregates (e.g. a count-per-agent query) and never by returning individual submission records to Admin-facing code.

**Open question, not yet solved:** if an agent reports that a form "was never sent," Admin currently has no way to investigate, since Admin cannot see submission-level records. This needs a resolution before real customers are onboarded — logged here so it is designed deliberately in a later stage rather than discovered during a support incident.
## 12. Form Mapping
Inside each PDF file there are fill fields with technical names that are not human-readable. In addition, the text layer of the forms cannot be read programmatically.
Therefore manual mapping work is required for each form: linking each field in the form to the question it represents. This is repetitive work that is not programming, and it constitutes a significant time component for each additional company.
The mapping duration per form will be measured in practice, and will serve as a basis for pricing future expansions.
The scope of forms included in the agreed price — the number of companies, the number of exclusion forms per company, and the number of standalone forms in the repository — is defined in the memorandum of understanding. A form or company beyond the quantity set there will be priced separately.

**Insurance types per company.** The set of insurance types offered by a company is not fixed at a single type, and is not the same across companies — it must be treated as data, not a hardcoded list, the same principle already applied to exclusion forms and rules above. Migdal, for example, splits into four: life insurance, health insurance, mortgage life insurance, and abbreviated health insurance — not two. Each insurance type is effectively its own base declaration form to be mapped, so this directly affects the form count used for scope and pricing above, and companies should not be assumed to share the same number of types as one another.
## 13. Platform Support
The Client requested that the filling interface resemble the official form. This requirement necessitates a fixed page layout, and therefore the interface is adapted for use on a computer.
If the Client sees a need, the interface will be adapted for use on a phone, subject to what is agreed in the memorandum of understanding.
## 14. Technology

## 15. Execution Stages
The work will be carried out in stages, and each stage will be presented for approval before moving on.
## 1. Setting up infrastructure, agent registration and separation between them
## 2. Mapping the first form and building the filling screen for the insured
## 3. First deployment to the cloud — early and not at the end, in order to detect infrastructure problems while fixing them is cheap
## 4. The exclusion rules engine and the exclusion forms
## 5. Signature, filling the official form and sending the emails
## 6. The standalone forms repository
## 7. Trial period, monthly subscription and automatic billing
## 8. Expansion to the remaining insurance companies and forms
## 9. Testing, error handling, training and delivery
## 16. What the Client Provides
All the forms in digital format with fill fields, before work begins
Definition of the exclusion rules for each company
A list of the forms to be included in the standalone forms repository
Opening the cloud and payment processing accounts, in her name and at her expense
Terms of use and privacy policy
Answering questions within a reasonable time, through the Client
Approval of each stage before moving on to the next stage

| Who | What they do |
| --- | --- |
| Agent | Registers, sends forms to insured clients, sees only their own requests |
| Insured | Enters through a personal link, fills in their form. Does not register and has no password |

| Component | Technology |
| --- | --- |
| User interface | React + TypeScript |
| Server | Java |
| Filling PDF forms | Apache PDFBox — free license |
| Hebrew text in forms | java.text.Bidi — built into the language |
| Database | PostgreSQL |
| Sending emails | Dedicated service with a verified domain |
| Payment processing | Israeli provider with a secure payment page and tokenization |
| Storage | Managed cloud service |
