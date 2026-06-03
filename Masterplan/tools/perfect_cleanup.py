import os
import re

MASTERPLAN = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\MASTERPLAN.md'
TODOLATER = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\ToDoLater.md'
REFERENCE = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\REFERENCE.md'
COMPLETED = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\Completed_Implementations.md'

with open(MASTERPLAN, 'r', encoding='utf-8') as f:
    content = f.read()

# We will split the document into blocks using `\n## ` and `\n### `
# But wait, keeping the exact subtext is important.
# Let's use a line-by-line state machine that respects the markdown structure.

lines = content.split('\n')

masterplan_out = []
todolater_out = ["# To Do Later (Wishlist & Backlog)\n\n"]
reference_out = ["# CustomBlocks Reference & Gotchas\n\n"]

current_section = "masterplan" # can be 'reference', 'todolater', 'masterplan', 'completed'
buffer = []

def flush_buffer(section):
    if not buffer: return
    text = '\n'.join(buffer) + '\n'
    if section == "reference":
        reference_out.append(text)
    elif section == "todolater":
        todolater_out.append(text)
    elif section == "completed":
        pass # Discard, already in Completed_Implementations.md
    else:
        masterplan_out.append(text)
    buffer.clear()

i = 0
in_group_9 = False
session_7_confirmed = ['NF2', 'COL1', 'COL2', 'PACK1', 'PACK2', 'COL11']

while i < len(lines):
    line = lines[i]
    
    # Check for Reference section
    if line.startswith('## Reference'):
        flush_buffer(current_section)
        current_section = "reference"
        buffer.append(line)
        i += 1
        continue
        
    # Check for Group 9 Backlog
    if line.startswith('## Group 9 — Backlog'):
        flush_buffer(current_section)
        current_section = "todolater"
        buffer.append(line)
        in_group_9 = True
        i += 1
        continue

    # Check for top-level headers that reset the section back to masterplan
    if line.startswith('## ') and not line.startswith('## Reference') and not line.startswith('## Group 9'):
        if current_section == "reference" or in_group_9:
            flush_buffer(current_section)
            current_section = "masterplan"
            in_group_9 = False
        
        # If it's a regular header, just append it to masterplan
        if current_section == "masterplan":
            buffer.append(line)
        else:
            buffer.append(line)
        i += 1
        continue

    # Check for Items ###
    if line.startswith('### '):
        flush_buffer(current_section)
        
        # Read the whole item block until the next ## or ###
        item_lines = [line]
        i += 1
        while i < len(lines) and not lines[i].startswith('## ') and not lines[i].startswith('### '):
            item_lines.append(lines[i])
            i += 1
            
        item_text = '\n'.join(item_lines)
        
        # Determine where this item goes
        target = current_section
        
        if '✅ CONFIRMED' in item_text or '🎮 BUILT AND TESTED' in item_text:
            target = "completed"
        elif any(f"### {x} " in item_text or f"[{x}]" in item_text for x in session_7_confirmed):
            target = "completed"
        elif '❌ NOT STARTED' in item_text or '❌' in item_text:
            target = "todolater"
            
        if target == "reference":
            reference_out.append(item_text + '\n')
        elif target == "todolater":
            todolater_out.append(item_text + '\n')
        elif target == "completed":
            pass # Discard
        else:
            # We add yaml frontmatter if it's an active item!
            # Extract ID
            match = re.search(r'###\s*(?:\[.*?\])?\s*([A-Za-z0-9+-]+)(?:\s*—|$)', item_lines[0])
            item_id = match.group(1) if match else "UNKNOWN"
            # Strip markdown formatting from ID if any
            item_id = item_id.replace('*', '').replace('~', '')
            
            frontmatter = f"---\nid: {item_id}\n---\n"
            masterplan_out.append(frontmatter + item_text + '\n')
            
        continue # skip the i += 1 at the end since we already advanced i

    # Normal line
    buffer.append(line)
    i += 1

flush_buffer(current_section)

with open(TODOLATER, 'w', encoding='utf-8') as f:
    f.write(''.join(todolater_out))

with open(REFERENCE, 'w', encoding='utf-8') as f:
    f.write(''.join(reference_out))

with open(MASTERPLAN, 'w', encoding='utf-8') as f:
    f.write(''.join(masterplan_out))

print("Cleanup script 2.0 finished!")
