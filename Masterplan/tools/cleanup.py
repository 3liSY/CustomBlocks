import os
import re

masterplan_path = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\MASTERPLAN.md'
todo_path = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\ToDoLater.md'

with open(masterplan_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Split by lines
lines = content.split('\n')

todo_items = []
active_items_by_column = {
    'broken': [],
    'under_construction': [],
    'ready': [],
    'blocked': []
}

current_group_name = ""
current_item_lines = []
current_item_state = ""
in_group_9_table = False
group_9_table_lines = []

known_issue_lines = []

i = 0
while i < len(lines):
    line = lines[i]
    
    if line.startswith('## Group 9 — Backlog'):
        in_group_9_table = True
        group_9_table_lines.append(line)
        i += 1
        continue
        
    if in_group_9_table:
        if line.startswith('## Known Undiagnosable Issue'):
            in_group_9_table = False
            known_issue_lines.append(line)
        elif line.startswith('## 5. 💬 Blocked') or line.startswith('## 6. ❌ Backlog'):
            in_group_9_table = False
            # process the line normally
        else:
            group_9_table_lines.append(line)
            i += 1
            continue

    if line.startswith('## Known Undiagnosable Issue'):
        known_issue_lines.append(line)
        i += 1
        while i < len(lines) and not lines[i].startswith('##'):
            known_issue_lines.append(lines[i])
            i += 1
        continue

    # Track current group for tagging
    match = re.match(r'^## Group \d+ — (.+)', line)
    if match:
        current_group_name = match.group(1).strip()
        
    # We found an item!
    if line.startswith('### '):
        # Save previous item if exists
        if current_item_lines:
            # classify previous item
            block = '\n'.join(current_item_lines)
            if '❌ NOT STARTED' in current_item_state or 'Backlog' in current_item_state or 'PARTIAL' in current_item_state or 'Group 6' in block:
                todo_items.append(block)
            elif '🔴 BROKEN' in current_item_state or 'INVESTIGATE' in current_item_state:
                active_items_by_column['broken'].append(block)
            elif '🏗️' in current_item_state or 'UNDER CONSTRUCTION' in current_item_state:
                active_items_by_column['under_construction'].append(block)
            elif '⏳' in current_item_state or 'BUILT' in current_item_state or 'READY' in current_item_state or 'UNKNOWN' in current_item_state:
                active_items_by_column['ready'].append(block)
            elif '💬' in current_item_state or 'DISCUSS' in current_item_state:
                active_items_by_column['blocked'].append(block)
            else:
                todo_items.append(block) # Default to todo
                
        # Start new item
        item_title = line[4:].strip()
        # Extract ID from title
        id_match = re.search(r'([A-Z0-9]+) —', item_title)
        item_id = id_match.group(1) if id_match else "UNKNOWN"
        
        # Add YAML Frontmatter
        frontmatter = f"---\nid: {item_id}\ntag: {current_group_name}\n---\n"
        
        # Rewrite title with tag
        if current_group_name and f"[{current_group_name}]" not in item_title:
            new_title = f"### [{current_group_name}] {item_title}"
        else:
            new_title = line
            
        current_item_lines = [frontmatter + new_title]
        current_item_state = ""
    elif current_item_lines:
        current_item_lines.append(line)
        if line.startswith('**State:**'):
            current_item_state = line
            
    i += 1

# Process the very last item
if current_item_lines:
    block = '\n'.join(current_item_lines)
    if '❌' in current_item_state or 'NOT STARTED' in current_item_state or 'PARTIAL' in current_item_state:
        todo_items.append(block)
    elif '🔴' in current_item_state or 'BROKEN' in current_item_state or 'INVESTIGATE' in current_item_state:
        active_items_by_column['broken'].append(block)
    elif '🏗️' in current_item_state or 'UNDER CONSTRUCTION' in current_item_state:
        active_items_by_column['under_construction'].append(block)
    elif '⏳' in current_item_state or 'BUILT' in current_item_state or 'READY' in current_item_state or 'UNKNOWN' in current_item_state:
        active_items_by_column['ready'].append(block)
    elif '💬' in current_item_state or 'DISCUSS' in current_item_state:
        active_items_by_column['blocked'].append(block)
    else:
        todo_items.append(block)

# WRITE MASTERPLAN
with open(masterplan_path, 'w', encoding='utf-8') as f:
    f.write("**Current Active Session:** (Look at the active file in the `Sub_Plans/` directory)\n\n")
    f.write("---\n\n")
    
    f.write("## 🔴 Broken / Investigate\n\n")
    if known_issue_lines:
        f.write('\n'.join(known_issue_lines) + '\n\n')
    for item in active_items_by_column['broken']:
        f.write(item + '\n\n')
        
    f.write("## 🏗️ Under Construction\n\n")
    for item in active_items_by_column['under_construction']:
        f.write(item + '\n\n')
        
    f.write("## ⏳ Ready for Test\n\n")
    for item in active_items_by_column['ready']:
        f.write(item + '\n\n')
        
    f.write("## 💬 Blocked / Needs Discussion\n\n")
    for item in active_items_by_column['blocked']:
        f.write(item + '\n\n')

# WRITE TODOLATER
with open(todo_path, 'w', encoding='utf-8') as f:
    f.write("# To Do Later (Wishlist & Backlog)\n\n")
    f.write("Items that are not urgent and not actively being worked on.\n\n")
    
    if group_9_table_lines:
        f.write('\n'.join(group_9_table_lines) + '\n\n')
        
    for item in todo_items:
        f.write(item + '\n\n')

print(f"Cleanup complete. Masterplan has {len(active_items_by_column['broken']) + len(active_items_by_column['under_construction']) + len(active_items_by_column['ready']) + len(active_items_by_column['blocked'])} active items.")
print(f"ToDoLater has {len(todo_items)} items + the group 9 table.")
