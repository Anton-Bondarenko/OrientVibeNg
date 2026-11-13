#!/usr/bin/env python
import pathlib

errlog = pathlib.Path('errlog.txt').read_text(encoding='utf-8')
lines = [l for l in errlog.splitlines() if 'e: file:' in l]

# Count total
total = len(lines)
print(f'Total errors: {total}')

# Count per file - extract just the module path after file:///
from collections import Counter
files = Counter()
for l in lines:
    # e: file:///F:/RestoredOrientNg/.../app/src/.../SomeFile.kt:123: msg
    fidx = l.find('file:///')
    if fidx >= 0:
        rest = l[fidx+7:]  # after file:///
        dotkt = rest.find('.kt:')
        if dotkt > 0:
            fname = rest[:dotkt+3]  # includes .kt
            files[fname] += 1

for f, c in files.most_common(20):
    print(f'{c}: {f}')
