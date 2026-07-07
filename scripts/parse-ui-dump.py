import re
import sys

xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if not t.strip():
        continue
    cx, cy = (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2
    print(f"{t!r:30} tap=({cx},{cy})")
