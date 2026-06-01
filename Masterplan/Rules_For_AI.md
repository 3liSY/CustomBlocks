# Sacred Rules for AI Assistants

> **AI: Read this file carefully before acting. These rules are non-negotiable.**

1. **"Nothing is ✅ DONE until tested in-game by dev."**
   You have no Minecraft client. You cannot verify features yourself. Do not claim something is "done" until the developer explicitly confirms it works in-game.

2. **"Check for UTF-8 BOM before debugging builds."**
   If a build fails randomly with weird syntax errors, check if the file was saved with UTF-8 BOM or has curly quotes (`“` or `”`) injected by mistake. This happens frequently.

3. **"Update the active batch file proactively."**
   When you finish writing code for an issue, immediately go to the active batch file (e.g., `01_Fix...md`) and check off `[x] Code Written`. Do not wait to be asked.

4. **"Never write code without discussing the design first."**
   Do not guess. Do not pull features from the backlog without permission. Do not implement grand overhauls. Fix exactly what is asked.

5. **"Follow the Royal Directive very specifically."**
   Always check the `THE_ROYAL_DIRECTIVE.md` file located right here in the `Masterplan/` folder. It contains deep history and strict boundaries (e.g., "Never make a plan with more than 5 items"). Respect it unconditionally.

6. **"Use the Sub-Plan Template."**
   When pulling issues from the backlog to create a new batch file, you MUST format the new file exactly matching `00_Sub_Plan_Template.md`. Keep all the technical details (root causes, files), but strip out all conversational fluff.
