import os
import re

masterplan_path = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\MASTERPLAN.md'
completed_path = r'c:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\Masterplan\Completed_Implementations.md'

with open(masterplan_path, 'r', encoding='utf-8') as f:
    content = f.read()

# We will split the document into blocks using a regex that matches ### and ## headings.
blocks = re.split(r'\n(?=#{2,3} )', '\n' + content)[1:]

new_masterplan = []
completed_items = []

session_7_confirmed = ['NF2', 'COL1', 'COL2', 'PACK1', 'PACK2', 'COL11']

for block in blocks:
    if block.startswith('### '):
        # Extract ID (e.g. "### ~~[MOVED to...] PACK2 — ...")
        match = re.match(r'###\s*(?:~~)?(?:\[.*?\])?\s*([A-Za-z0-9+-]+)(?:\s*—|$)', block)
        task_id = match.group(1) if match else ""
        
        is_completed = False
        if '✅ CONFIRMED' in block or '🎮 BUILT AND TESTED' in block:
            is_completed = True
        elif task_id in session_7_confirmed:
            is_completed = True
            
        if is_completed:
            # Clean up the heading
            clean_block = re.sub(r'^(###\s*)~~\[.*?\]\s*', r'\1', block)
            clean_block = clean_block.replace('~~', '')
            # Update state text to reflect it is done
            clean_block = re.sub(r'(\*\*State:\*\* ).*?\n', r'\1✅ CONFIRMED WORKING IN-GAME\n', clean_block, count=1)
            completed_items.append(clean_block)
        else:
            new_masterplan.append(block)
    else:
        # Skip the old Archive heading
        if 'Archive (✅ WORKING)' in block:
            pass
        else:
            new_masterplan.append(block)

# Clean up any empty Group headings from new_masterplan
final_masterplan = []
for i, block in enumerate(new_masterplan):
    if block.startswith('## Group '):
        # check if it's just the header and empty space/horizontal rules
        text_only = re.sub(r'^## Group.*?\n', '', block).replace('-', '').strip()
        # If it has no actual content inside and the next block is a heading, skip it
        if not text_only and (i + 1 == len(new_masterplan) or new_masterplan[i+1].startswith('## ')):
            continue 
    final_masterplan.append(block)

with open(completed_path, 'w', encoding='utf-8') as f:
    f.write("# Completed Implementations\n\n")
    f.write("\n".join(completed_items))

with open(masterplan_path, 'w', encoding='utf-8') as f:
    f.write("\n".join(final_masterplan))

print(f"Surgically moved {len(completed_items)} completed items to {completed_path}!")
