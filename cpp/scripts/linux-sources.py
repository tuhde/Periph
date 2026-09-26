#!/usr/bin/env python3
"""Print the .cpp files a Linux app needs, one per line.

Usage: linux-sources.py <app dir>

Linux apps (cpp/examples/linux/..., cpp/tests/*/*_test_linux, *_test_unit)
have no build files. This collects the app's own .cpp files plus the .cpp
next to every header they include, transitively, from cpp/src/connection and
cpp/src/chips/*. Used by cpp/scripts/build-all.sh and cpp/test_linux.sh.
"""
import glob
import os
import re
import sys

app = sys.argv[1]
src = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src')
search = [os.path.join(src, 'connection')] + sorted(glob.glob(os.path.join(src, 'chips', '*')))
inc = re.compile(r'^\s*#\s*include\s*["<]([^">]+)[">]', re.M)
seen, out = set(), []
def visit(path):
    if path in seen:
        return
    seen.add(path)
    if path.endswith('.cpp'):
        out.append(path)
    for name in inc.findall(open(path, errors='replace').read()):
        for d in [os.path.dirname(path)] + search:
            p = os.path.join(d, name)
            if os.path.isfile(p):
                visit(os.path.realpath(p))
                cpp = os.path.splitext(p)[0] + '.cpp'
                if os.path.isfile(cpp):
                    visit(os.path.realpath(cpp))
                break
for f in sorted(os.listdir(app)):
    if f.endswith('.cpp'):
        visit(os.path.realpath(os.path.join(app, f)))
print('\n'.join(out))
