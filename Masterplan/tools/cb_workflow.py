import sys
import os
import re
import datetime
import subprocess

MASTERPLAN_PATH = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\MASTERPLAN.md'
SUB_PLANS_DIR = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\Sub_Plans'
ARCHIVE_DIR = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\Archive_Sub_Plans'
ACTIVE_SESSION = os.path.join(SUB_PLANS_DIR, 'active_session.md')

def git_command(*args):
    try:
        subprocess.run(['git'] + list(args), cwd=os.path.dirname(MASTERPLAN_PATH), check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as e:
        print(f"Git command failed: git {' '.join(args)}")
        print(e.stderr.decode('utf-8'))

def start_session(issue_ids):
    if os.path.exists(ACTIVE_SESSION):
        print(f"ERROR: An active session already exists at {ACTIVE_SESSION}!")
        print("Please run finish.bat first.")
        return

    with open(MASTERPLAN_PATH, 'r', encoding='utf-8') as f:
        content = f.read()

    extracted_blocks = []
    
    # We look for blocks bounded by frontmatter ---
    # Example:
    # ---
    # id: COL12
    # tag: Color Tools
    # ---
    # ### [Color Tools] COL12 — Title
    
    for issue_id in issue_ids:
        # Regex to find the block
        # We find --- id: X ... until the next --- or EOF or ## (next kanban column)
        pattern = re.compile(rf"(^---\nid: {issue_id}\n.*?(?=\n---|## \S|## 🔴|## 🏗️|## ⏳|## 💬|\Z))", re.MULTILINE | re.DOTALL)
        match = pattern.search(content)
        if match:
            block = match.group(1).strip()
            extracted_blocks.append(block)
            content = content.replace(match.group(1), "") # remove from masterplan
        else:
            print(f"WARNING: Issue {issue_id} not found in MASTERPLAN.md!")

    if not extracted_blocks:
        print("No valid issues found. Aborting.")
        return

    # Clean up empty lines in masterplan
    content = re.sub(r'\n{3,}', '\n\n', content)
    with open(MASTERPLAN_PATH, 'w', encoding='utf-8') as f:
        f.write(content)

    # Generate active_session.md
    with open(ACTIVE_SESSION, 'w', encoding='utf-8') as f:
        f.write("# Active Session\n\n")
        f.write("> **AI HANDSHAKE:** Hello AI. I am your developer. Read `Rules_For_AI.md` and `THE_ROYAL_DIRECTIVE.md` before we start. We are executing this Sub-Plan. Do not do anything else. When finished, fill out `Completed_Implementations.md`.\n\n")
        f.write("## Tasks for this Session:\n")
        for i, block in enumerate(extracted_blocks):
            f.write(f"\n{block}\n")
            f.write("\n- [ ] Code Written\n- [ ] Tested In-Game\n")
            f.write("\n---\n")

    print(f"Session started! Created {ACTIVE_SESSION} with {len(extracted_blocks)} issues.")
    
    # Git
    branch_name = f"feature/{'-'.join(issue_ids)}"
    print(f"Creating Git branch: {branch_name}...")
    git_command("checkout", "-b", branch_name)

def finish_session():
    if not os.path.exists(ACTIVE_SESSION):
        print(f"ERROR: No active session found at {ACTIVE_SESSION}!")
        return

    timestamp = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    archive_name = f"{timestamp}_Session.md"
    archive_path = os.path.join(ARCHIVE_DIR, archive_name)

    os.makedirs(ARCHIVE_DIR, exist_ok=True)
    os.rename(ACTIVE_SESSION, archive_path)
    
    print(f"Session finished! Archived to {archive_path}.")
    print("WARNING for AI: Do not forget to manually append the fix details to Completed_Implementations.md!")

    print("Committing to Git...")
    git_command("add", ".")
    git_command("commit", "-m", f"Completed session {timestamp}")
    print("Git commit complete!")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: py cb_workflow.py <start|finish> [issue_ids...]")
        sys.exit(1)

    command = sys.argv[1].lower()
    if command == "start":
        start_session(sys.argv[2:])
    elif command == "finish":
        finish_session()
    else:
        print(f"Unknown command: {command}")
