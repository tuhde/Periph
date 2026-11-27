#!/usr/bin/env python3
"""Remove classifier lines that the installed trove-classifiers doesn't know.

Usage: strip-classifiers.py PYPROJECT_TOML

Runs on the package build's copy of python/pyproject.toml only (see
debian/rules). Without the trove_classifiers module setuptools doesn't
validate classifiers, so there is nothing to do.
"""
import re
import sys

try:
    from trove_classifiers import classifiers as known
except ImportError:
    sys.exit(0)

path = sys.argv[1]
lines = open(path, encoding='utf-8').read().splitlines(keepends=True)
out, inside = [], False
for line in lines:
    if re.match(r'\s*classifiers\s*=\s*\[', line):
        inside = True
    elif inside and line.strip() == ']':
        inside = False
    elif inside:
        m = re.match(r'\s*"([^"]+)"\s*,?\s*$', line)
        if m and m.group(1) not in known:
            print('dropping classifier unknown to trove-classifiers: ' + m.group(1))
            continue
    out.append(line)
open(path, 'w', encoding='utf-8').writelines(out)
