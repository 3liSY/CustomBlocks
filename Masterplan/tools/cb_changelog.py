import os
import re

COMPLETED_FILE = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\Completed_Implementations.md'
CHANGELOG_FILE = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\CHANGELOG.md'

def generate_changelog():
    if not os.path.exists(COMPLETED_FILE):
        print("No completed implementations found!")
        return

    with open(COMPLETED_FILE, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    table_lines = []
    in_table = False
    for line in lines:
        if line.startswith('| ID | Issue Name'):
            in_table = True
            continue
        if in_table and line.startswith('|---'):
            continue
        if in_table and line.startswith('|'):
            table_lines.append(line)
        elif in_table and not line.strip():
            # End of table
            in_table = False

    if not table_lines:
        print("Could not find the summary table in Completed_Implementations.md!")
        return

    changelog = ["# CustomBlocks - Auto-Generated Changelog\n", "## 🚀 Recent Fixes and Features\n"]

    for row in table_lines:
        cols = [c.strip() for c in row.split('|') if c.strip()]
        if len(cols) >= 4:
            issue_id = cols[0].replace('*', '')
            name = cols[1].replace('`', '')
            why = cols[2]
            how = cols[3]
            
            changelog.append(f"### ✨ {name}")
            changelog.append(f"- **The Problem:** {why}")
            changelog.append(f"- **The Fix:** {how}")
            changelog.append("")

    with open(CHANGELOG_FILE, 'w', encoding='utf-8') as f:
        f.write('\n'.join(changelog))

    print(f"Successfully generated player-facing changelog at {CHANGELOG_FILE}!")

if __name__ == "__main__":
    generate_changelog()
