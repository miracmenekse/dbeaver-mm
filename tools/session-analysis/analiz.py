#!/usr/bin/env python3
"""dbeaver-mm K51: turns session recordings (.jsonl or K50 .zip) into a process skeleton.

For each session (nickname + name): the ordered steps with repeats collapsed, then the steps that
show up in most sessions, i.e. the common configuration path. Only stdlib.

    python3 analiz.py ~/.dbeaver-mm/kayit/*.jsonl ~/Desktop/*.zip > surec.md
"""
import json
import sys
import zipfile
from collections import Counter, defaultdict


def lines(paths):
    for path in paths:
        if path.endswith(".zip"):
            with zipfile.ZipFile(path) as z:
                for n in z.namelist():
                    if n.endswith(".jsonl"):
                        yield from z.read(n).decode("utf-8").splitlines()
        else:
            with open(path, encoding="utf-8") as f:
                yield from f


def sessions(paths):
    result = defaultdict(list)
    for line in lines(paths):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("session") and e.get("type") not in ("oturum_basla", "oturum_bitir", "dusen"):
            step = e["type"] + (" " + e["target"] if e.get("target") else "")
            steps = result[(e.get("user") or "?", e["session"])]
            if not steps or steps[-1] != step:
                steps.append(step)
    return result


def report(result):
    out = []
    common = Counter()
    for (user, name), steps in sorted(result.items()):
        out.append(f"## {name} ({user})\n")
        out += [f"{i}. {s}" for i, s in enumerate(steps, 1)]
        out.append("")
        common.update(set(steps))
    if len(result) > 1:
        out.append("## Common steps\n")
        out += [f"- {s}: {n}/{len(result)} sessions" for s, n in common.most_common() if n > 1]
    return "\n".join(out)


def demo():
    ls = [json.dumps(d) for d in [
        {"user": "Frodo", "session": "T1", "type": "tablo_ac", "target": "pcm.a"},
        {"user": "Frodo", "session": "T1", "type": "tablo_ac", "target": "pcm.a"},
        {"user": "Frodo", "session": "T1", "type": "dml", "target": "pcm.a"},
        {"user": "Sam", "session": "T2", "type": "tablo_ac", "target": "pcm.a"},
        {"user": "Sam", "session": None, "type": "tablo_ac", "target": "x"},
    ]]
    global lines
    real, lines = lines, lambda _: ls
    try:
        r = sessions([])
    finally:
        lines = real
    assert r[("Frodo", "T1")] == ["tablo_ac pcm.a", "dml pcm.a"], r
    assert "- tablo_ac pcm.a: 2/2 sessions" in report(r)


if __name__ == "__main__":
    if sys.argv[1:] == ["--test"]:
        demo()
        print("ok")
    else:
        print(report(sessions(sys.argv[1:])))
