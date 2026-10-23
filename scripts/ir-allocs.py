#!/usr/bin/env python3
"""Reports GC allocations reachable from functions in Scala Native's generated LLVM IR.

Usage: ir-allocs.py <dir with .ll files> <regex for mangled function names>

For each matching function, follows direct calls (up to a fixed depth) and lists
the classes passed to the GC allocator. Allocations of exceptions/errors and the
code that only runs while throwing (message building) are reported separately.
Exits with status 1 if a matching function allocates anything else.

Limitations: indirect (virtual) calls are not followed, only counted; a direct
call on the throwing path is not distinguished from one on the normal path,
except for the known throw helpers.
"""
import collections
import glob
import re
import sys

FN = re.compile(r'^define [^@]*@"([^"]+)"')
CALL = re.compile(r'call [^@%]*@"([^"]+)"\((.*)')
INDIRECT = re.compile(r'call [^@]*%[_0-9a-zA-Z.]+\(')
CLASS = re.compile(r'@"_SM\d+([^"]+?)G4type"')
# Classes allocated when building an exception and its message.
THROW_PATH = re.compile(r'(Exception|Error)$|^java\.lang\.(String|StringBuilder)$|^scala\.scalanative\.runtime\.CharArray$')

calls, allocs, indirect = collections.defaultdict(set), collections.defaultdict(list), collections.Counter()
current = None
for path in glob.glob(sys.argv[1] + "/*.ll"):
    for line in open(path, errors="replace"):
        m = FN.match(line)
        if m:
            current = m.group(1)
            continue
        if line.startswith("}"):
            current = None
            continue
        if not current:
            continue
        for c in CALL.finditer(line):
            callee, rest = c.group(1), c.group(2)
            if callee.startswith(("scalanative_GC_alloc", "scalanative_alloc")):
                k = CLASS.search(rest)
                allocs[current].append(k.group(1) if k else "?")
            else:
                calls[current].add(callee)
        if INDIRECT.search(line):
            indirect[current] += 1


def demangle(name):
    m = re.match(r"_SM\d+.*?\$?D(\d+)", name)
    return name[m.end():m.end() + int(m.group(1))] if m else name


failed = False
for root in sorted(f for f in calls.keys() | allocs.keys() if re.match(sys.argv[2], f)):
    seen, found, n_indirect, stack = set(), set(), 0, [(root, 0)]
    while stack:
        fn, depth = stack.pop()
        if fn in seen or depth > 8 or "throw" in fn.lower():
            continue
        seen.add(fn)
        n_indirect += indirect[fn]
        found.update(allocs[fn])
        stack += [(c, depth + 1) for c in calls[fn]]
    normal = sorted(c for c in found if not THROW_PATH.search(c))
    throwing = sorted(c for c in found if THROW_PATH.search(c))
    failed |= bool(normal)
    print(f"{demangle(root):24} {'ALLOCATES' if normal else 'ok':9} indirect-calls={n_indirect}")
    for c in normal:
        print(f"    allocates   {c}")
    for c in throwing:
        print(f"    when throws {c}")
sys.exit(1 if failed else 0)
