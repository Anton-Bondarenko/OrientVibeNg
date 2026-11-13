#!/usr/bin/env python3
import subprocess
import os

os.chdir(r"F:\RestoredOrientNg\gitExperiment\orientvibeNg")

result = subprocess.run(
    ["cmd", "/c", "gradlew", ":app:compileDebugKotlin"],
    capture_output=True, text=True, encoding='latin1', errors='replace'
)

with open("build-output-v8.txt", "w", encoding="utf-8") as f:
    f.write(result.stdout)
    f.write(result.stderr)

# Count errors from combined output
all_output = result.stdout + result.stderr
error_lines = [l for l in all_output.split('\n') if l.startswith('e:')]
print(f"Total errors: {len(error_lines)}")

# Count per file
from collections import Counter, defaultdict
import re

file_counts = Counter()
for line in error_lines:
    match = re.search(r'file:///F:/(.+?):\d+:\d+', line)
    if not match:
        match = re.search(r"file:///(\w):/(.+?):\d+:\d+", line)
    if match:
        filepath = match.group(2) or match.group(1)
        file_counts[filepath] += 1

print("\nTop 20 error-producing files:")
for f, c in file_counts.most_common(20):
    print(f"  {c:>6}  {f}")

# Check if build succeeded
if "BUILD SUCCESSFUL" in result.stdout or "BUILD SUCCESSFUL" in result.stderr:
    print("\n*** BUILD SUCCESSFUL ***")
else:
    print("\nBuild FAILED")
    # Print last few lines for context
    all_text = result.stdout + result.stderr
    lines = all_text.strip().split('\n')
    print(f"\nLast 10 lines:")
    for l in lines[-10:]:
        print(l)
