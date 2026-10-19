import re

lines = open(r'app/src/main/java/ru/bondarenko/orientvibe/ng/screen/AutoModeScreen.kt').readlines()
d = 0
boundaries = list(range(1, 90)) + [291, 301, 302, 312, 375, 380, 594, 595, 654, 655] + list(range(810, 830))

for i, line in enumerate(lines, 1):
    code = line
    # Strip strings
    result = []
    in_str = False
    for j, c in enumerate(code):
        if c == '"' and (j == 0 or code[j-1] != '\\'):
            in_str = not in_str
            continue
        if not in_str:
            result.append(c)
    code = ''.join(result).split('//')[0]

    opens = code.count('{')
    closes = code.count('}')
    old_d = d
    d += opens - closes

    s = line.strip()
    show = i in boundaries or 'fun ' in s or '@Composable' in line
    show = show or ('private ' in s and 'fun ' in s)
    show = show or (i >= 594 and i <= 600)

    if opens + closes > 0 and show:
        print(f'L{i:4d}: d{old_d:+d}->{d:+d} ({opens}/{closes}) {s[:100]}')

print(f'\nFinal depth: {d}')

# Also check where depth-0 transitions happen around AutoModeNavBottomPanel and AutoModePhase
print('\n--- Depth near AutoModeNavBottomPanel (~line 820) ---')
for i in range(810, 830):
    if i < len(lines):
        print(f'  L{i}: depth={d}')

# Check the very end
print(f'\n--- Last 15 lines ---')
for i in range(len(lines) - 15, len(lines)):
    print(f'  L{i+1}: {lines[i].rstrip()[:100]}')
