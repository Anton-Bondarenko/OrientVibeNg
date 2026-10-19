lines = open(r'app/src/main/java/ru/bondarenko/orientvibe/ng/screen/AutoModeScreen.kt').readlines()

# Check parentheses too - they can create scope in lambda chains
p = 0  # parentheses nesting level (inside lambdas)
d = 0  # brace depth
for i, line in enumerate(lines, 1):
    code = line
    # Strip strings char by char
    result = []
    in_str = False
    for j, c in enumerate(code):
        if c == '"' and (j == 0 or code[j-1] != '\\'):
            in_str = not in_str
            continue
        if not in_str:
            result.append(c)
    code = ''.join(result).split('//')[0]

    # Count braces for depth
    opens_b = code.count('{')
    closes_b = code.count('}')

    # Count parens - opening ones that start lambdas (at end of line after function name)
    opens_p = 0
    closes_p = 0
    for c in code:
        if c == '(': opens_p += 1
        elif c == ')': closes_p += 1

    old_d = d
    p += opens_p - closes_p
    d += opens_b - closes_b

    # Show lines where depth changes significantly
    s = line.strip()
    if i >= 530 and i <= 620 and (opens_b > 0 or closes_b > 0):
        print(f'L{i:4d}: d{old_d:+d}->{d:+d} ({opens_b}/{closes_b}) p={p} {s[:100]}')

# Now check for mismatched parens that might create "function call scope"
print('\n--- Lines with many unmatched open parens ---')
p_count = 0
for i, line in enumerate(lines, 1):
    s = line
    result = []
    in_str = False
    for j, c in enumerate(s):
        if c == '"' and (j == 0 or s[j-1] != '\\'):
            in_str = not in_str
            continue
        if not in_str:
            result.append(c)
    code = ''.join(result)

    opens_p = code.count('(')
    closes_p = code.count(')')
    p_count += opens_p - closes_p

    # Flag lines where paren count gets very deep
    if abs(p_count) > 5:
        print(f'  L{i}: paren_depth={p_count}: {line.strip()[:80]}')
