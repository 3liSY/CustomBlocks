import os

def fix_file(path):
    # read as utf-8
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    orig = content
    # Common double-encoding corruptions of §
    content = content.replace('Ã‚Â§', '§')
    content = content.replace('Ãƒâ€šÃ‚Â§', '§')
    content = content.replace('Â§', '§')
    
    # Common corruptions of arrows, borders, symbols
    content = content.replace('Ã¢â€\x86â€™', '→')
    content = content.replace('Ã¢Å“Â¦', '✦')
    content = content.replace('Ã¢Â¬Â¡', '⬡')
    content = content.replace('ÃƒÂ¢Ã‚Â¬Ã‚Â¡', '⬡')
    content = content.replace('Ã¢â€\x9dâ‚¬', '─')
    content = content.replace('Ã¢â€\x9dÅ“', '├')
    content = content.replace('Ã¢â€\x9dâ€\x94', '└')
    content = content.replace('Ã¢â€\x9dâ€š', '│')
    content = content.replace('Ã¢â€“Â¶', '▶')
    content = content.replace('Ã¢Å“Å½', '✎')
    content = content.replace('Ã°Å¸â€\x9dÂ\x8d', '🔍')
    content = content.replace('Ã¢â‚¬â€\x9d', '—')
    content = content.replace('Ã¢Å“â€\x9d', '✔')
    content = content.replace('Ã¢Å“â€\x96', '✖')
    content = content.replace('Ã¢Â\x8fÂ¸', '⏸')
    content = content.replace('Ã¢â€”â‚¬', '◀')
    content = content.replace('Ã¢Å¡â„¢', '⚙')
    content = content.replace('Ã¢â„¢Â«', '♫')
    content = content.replace('Ã¢â‚¬Â¦', '…')

    if orig != content:
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Fixed {path}")

for root, _, files in os.walk('src/main/java'):
    for f in files:
        if f.endswith('.java'):
            fix_file(os.path.join(root, f))
