#!/usr/bin/env python3
"""UI (main) thread freeze summary from a JFR file (streams `jfr print` text, low memory).
usage: olc.py rec.jfr
- socket ops: DB round trips (jdk.SocketRead/Write, threshold 0), UI thread vs all, top UI call chains
- busy runs: consecutive non-idle main-thread samples (Execution + NativeMethod) >= 100 ms
"""
import collections, os, re, shutil, subprocess, sys

JFR = os.path.join(os.path.dirname(os.path.realpath(shutil.which('java'))), 'jfr')
IDLE = ('Display.sleep', 'g_main_context_iteration', 'gtk_main_iteration', 'Object.wait', 'Unsafe.park', 'Thread.sleep')
FORK = ('FkDictionary', 'Conf', 'InlineFk', 'Navigator', 'Spreadsheet', 'ResultSet', 'Grid', 'Fk', 'DBV', 'DBUtils',
        'Hint', 'Filter', 'Picker', 'Dictionary')
FRAME = re.compile(r'([\w$.]+)\(.*\)\s+line: (-?\d+)')


def parse(path, names):
    p = subprocess.Popen([JFR, 'print', '--stack-depth', '40', '--events', ','.join(names), path],
                         stdout=subprocess.PIPE, text=True, bufsize=1 << 20)
    ev = None
    for line in p.stdout:
        s = line.strip()
        if ev is None:
            if s.startswith('jdk.') and s.endswith('{'):
                ev = {'type': s[:-1].strip(), 'frames': [], 'main': False, 'ms': 0.0}
            continue
        if s.startswith('startTime ='):
            m = re.search(r'(\d+):(\d+):(\d+[.,]?\d*)', s)
            ev['t'] = int(m[1]) * 3600 + int(m[2]) * 60 + float(m[3].replace(',', '.'))
        elif s.startswith('duration ='):
            m = re.match(r'duration = ([\d.,]+) (\w+)', s)
            if m:
                ev['ms'] = float(m[1].replace(',', '.')) * {'ns': 1e-6, 'us': 1e-3, 'ms': 1, 's': 1000}.get(m[2], 0)
        elif s.startswith(('eventThread =', 'sampledThread =')):
            ev['main'] = s.split('=', 1)[1].strip().startswith('"main"')
        elif s == '}':
            yield ev
            ev = None
        else:
            m = FRAME.match(s)
            if m:
                parts = m[1].split('.')
                ev['frames'].append(f"{'.'.join(parts[-2:])}:{m[2]}")
    p.wait()


def site(fr):
    for f in fr:
        if any(k in f for k in FORK) and not f.startswith(('JDBC', 'Pg', 'Postgre')):
            return f
    return fr[0] if fr else '?'


def run(path):
    io_all = io_ui = 0
    ui_ms = 0.0
    chains = collections.Counter()
    samples = []
    for e in parse(path, ['jdk.SocketRead', 'jdk.SocketWrite', 'jdk.ExecutionSample', 'jdk.NativeMethodSample']):
        if e['type'].startswith('jdk.Socket'):
            io_all += 1
            if e['main']:
                io_ui += 1
                ui_ms += e['ms']
                chain = [f for f in e['frames'] if any(k in f for k in FORK) and not f.startswith(('JDBC', 'Pg'))]
                chains[' <- '.join(chain[:4]) or site(e['frames'])] += 1
        elif e['main'] and 't' in e:
            fr = e['frames']
            busy = bool(fr) and not any(i in f for f in fr[:8] for i in IDLE)
            samples.append((e['t'], busy, fr))
    print(f"socket ops: all threads {io_all}, UI thread {io_ui} ({ui_ms:.0f} ms blocked in socket)")
    for s, n in chains.most_common(6):
        print(f"  {n:6d}  {s}")
    samples.sort(key=lambda x: x[0])
    runs, cur = [], []
    for t, busy, fr in samples:
        if busy and cur and t - cur[-1][0] < 0.06:
            cur.append((t, fr))
        else:
            if len(cur) > 1:
                runs.append(cur)
            cur = [(t, fr)] if busy else []
    if len(cur) > 1:
        runs.append(cur)
    runs = sorted((r for r in runs if r[-1][0] - r[0][0] >= 0.1), key=lambda r: r[0][0] - r[-1][0])
    print(f"UI samples {len(samples)}, busy {sum(1 for s in samples if s[1])}; busy runs >=100ms: {len(runs)}, "
          f"total {sum(r[-1][0] - r[0][0] for r in runs) * 1000:.0f} ms")
    for r in runs[:6]:
        print(f"  {(r[-1][0] - r[0][0]) * 1000:6.0f} ms  {collections.Counter(site(fr) for _, fr in r).most_common(3)}")
    print("  busy sample sites:", collections.Counter(site(fr) for _, b, fr in samples if b).most_common(6))


if __name__ == '__main__':
    run(sys.argv[1])
