# The Masterplan System Tutorial

Welcome to the new modular project management system. Here is how to use it so things never get chaotic again.

### 1. How to Start a Session
When you are ready to code, simply tell the AI:
**"Start Session [X]"**

The AI will automatically:
1. Create a log file in `Session_Logs/`.
2. Ask you which issues you want to pull from the massive `MASTERPLAN.md` backlog.
3. Create a new numbered batch file inside `Sub_Plans/` (e.g., `Sub_Plans/02_Fix_GUI_Bugs.md`).
4. Cross out the items in the backlog with `~~[MOVED]~~` so they aren't tracked twice.

### 2. How to Work
Open your active batch file (e.g., `Sub_Plans/01_Fix_NF2_COL11_PACK2.md`). 
*   It will contain tracking checkboxes (`[ ] Root Cause Verified`, `[ ] Code Written`, `[ ] Tested In-Game`).
*   The AI must check these boxes off as work progresses.
*   Focus *only* on the issues in this file.

### 3. How to Close a Batch
When every single item in the batch file has `[x] Tested In-Game` checked off, the batch is done.
Physically drag the Markdown file into the `Completed_Batches/` folder to archive it. Then, start a new session!

---

### 4. How to Start a Brand New Chat
If your conversation is getting too long and you need to start a fresh chat, just copy and paste this exact prompt to the new AI so it doesn't break anything:

> *"Read `Masterplan/THE_ROYAL_DIRECTIVE.md` and `Masterplan/Rules_For_AI.md`. Once you prove you have read them, read our active batch file at `Masterplan/Sub_Plans/[YOUR_BATCH_FILE_NAME].md` and tell me what the plan is to fix the first item on the list. Wait for my permission before writing any code."*
