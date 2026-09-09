#!/usr/bin/env python3
"""Shared sigrok-capture + timing-check orchestration for chip conformance
checkers. See specs/testing_framework.md, "Conformance Implementation".

Each chip's own conformance/<category>/<chip>_conformance.py supplies:
  - decoder_id: matches sigrok/<chip>/pd.py's `id` (found via SIGROKDECODE_DIR,
    set below to this repo's sigrok/ directory - no system install needed)
  - channel_map: e.g. "scl=D0:sda=D1", built from testconfig_wiring's
    SIGROK_CHANNELS ("D0=SCL,D1=SDA" -> "scl=D0:sda=D1")
  - a CHECKS table: {check_name: (is_start, is_end)}, each a predicate over
    one decoded annotation's text (the same text sigrok/<chip>/pd.py already
    emits for manual PulseView verification - see each chip's own
    conformance script for which substrings key off which register).
  - trigger(check_name): drives the language's HIL binary/process so real
    bus traffic happens during the capture window

...then calls run_checks(), which does the rest: read the chip's
<chip>_timing.conf, start each check's timed capture, invoke trigger()
during that window (this is the "software-orchestrated, no hardware
trigger" scheme - the checker controls sequencing itself, avoiding
sigrok-cli's driver-dependent -t/--triggers syntax), decode, find the first
start/end annotation pair, compare the delta against the bound, and print
the PASS/FAIL/===DONE=== convention every other test level already uses.

Not yet verified against a real capture (no logic analyzer + wired chip in
the environment this was written in) - the sigrok-cli invocation shapes
and annotation line format below follow documented/observed sigrok-cli
behavior (see argparse --help output for -P/-A/--protocol-decoder-samplenum,
and libsigrokdecode's annotation output convention), but the first real
run against hardware may need small adjustments. See
specs/hil_conformance_checklist.md.
"""
import os
import re
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SIGROK_DECODE_DIR = str(REPO_ROOT / "sigrok")

_BOUND_RE = re.compile(r'^(\w+)_(min|max)_(\w+)$')
_CAPTURE_RE = re.compile(r'^(\w+)_(samplerate|capture_ms)$')
_UNIT_SECONDS = {'s': 1.0, 'ms': 1e-3, 'us': 1e-6}
_SR_SUFFIX = {'': 1.0, 'k': 1e3, 'm': 1e6, 'g': 1e9}

# sigrok-cli's -A output with --protocol-decoder-samplenum, one annotation
# per line: "<start_sample>-<end_sample> <decoder>-<row>: <text...>"
_ANNOTATION_RE = re.compile(r'^(\d+)-(\d+)\s+\S+:\s*(.*)$')


def parse_timing_conf(path):
    """<check>_min_<unit>/<check>_max_<unit> + <check>_samplerate/<check>_capture_ms
    -> {check: {'min': (val, unit), 'max': (val, unit), 'samplerate': str, 'capture_ms': str}}."""
    checks = {}
    for raw in Path(path).read_text().splitlines():
        line = raw.split('#', 1)[0].strip()
        if not line or '=' not in line:
            continue
        key, val = (p.strip() for p in line.split('=', 1))
        m = _BOUND_RE.match(key)
        if m:
            name, bound, unit = m.groups()
            checks.setdefault(name, {})[bound] = (float(val), unit)
            continue
        m = _CAPTURE_RE.match(key)
        if m:
            name, field = m.groups()
            checks.setdefault(name, {})[field] = val
    return checks


def _to_seconds(value, unit):
    return value * _UNIT_SECONDS[unit]


def _parse_samplerate(s):
    m = re.match(r'^([\d.]+)\s*([kmg]?)$', s.strip().lower())
    if not m:
        raise ValueError(f'unrecognized samplerate: {s!r}')
    value, suffix = m.groups()
    return float(value) * _SR_SUFFIX[suffix]


def _sigrok_env():
    env = os.environ.copy()
    existing = env.get('SIGROKDECODE_DIR', '')
    env['SIGROKDECODE_DIR'] = SIGROK_DECODE_DIR + (os.pathsep + existing if existing else '')
    return env


def capture(driver, conn, channel_names, samplerate, capture_ms, out_file):
    """Run a fixed-duration sigrok-cli capture to out_file (blocking).
    channel_names: plain comma-separated channel names to enable, e.g. "D0,D1"
    (this is sigrok-cli's own --channels syntax - not the i2c:scl=..:sda=..
    role mapping decode_annotations() needs, even though both are commonly
    derived from the same SIGROK_CHANNELS="D0=SCL,D1=SDA" testconfig value -
    see channels_from_sigrok_channels() and channel_names_from_sigrok_channels()."""
    driver_arg = driver + (f':conn={conn}' if conn else '')
    cmd = [
        'sigrok-cli', '--driver', driver_arg,
        '--config', f'samplerate={samplerate}',
        '--time', str(capture_ms),
        '--channels', channel_names,
        '-o', out_file,
    ]
    subprocess.run(cmd, check=True, env=_sigrok_env(), stdout=subprocess.DEVNULL)


def decode_annotations(sr_file, decoder_id, channel_map, protocol='i2c', extra_decoder_opts=''):
    """Return decoded annotations as a list of (start_sample, end_sample, text).
    channel_map: the stacked bus decoder's role mapping, e.g. "scl=D0:sda=D1"
    for i2c, "clk=D0:mosi=D1:miso=D2:cs=D3" for spi, or "din=D0" for neopixel -
    see channels_from_sigrok_channels(). protocol is which bus decoder the
    chip decoder stacks on (most chips: 'i2c'; SPI-only chips like MFRC522:
    'spi'; NeoPixel chips: 'neopixel') - check the chip decoder's own
    `inputs = [...]` to know which. extra_decoder_opts appends any non-channel
    decoder options the bus decoder needs, e.g. ":reset_us=50" for neopixel -
    check that decoder's own `options = [...]` for what it accepts.

    decoder_id=None means the chip has no separate stacked decoder because
    its own sigrok/<chip>/pd.py already consumes raw logic channels directly
    (`inputs = ['logic']`) and emits chip-specific annotations itself - e.g.
    HX711, whose 2-wire bit-bang protocol has no shared bus decoder to stack
    on the way I2C/SPI/NeoPixel chips do. In that case `protocol` names that
    self-contained decoder directly and nothing is appended after the
    channel map."""
    pd_arg = f'{protocol}:{channel_map}{extra_decoder_opts}'
    if decoder_id is not None:
        pd_arg += f',{decoder_id}'
    cmd = [
        'sigrok-cli', '-i', sr_file,
        '-P', pd_arg,
        '--protocol-decoder-samplenum',
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True, env=_sigrok_env())
    hits = []
    for line in result.stdout.splitlines():
        m = _ANNOTATION_RE.match(line)
        if m:
            hits.append((int(m.group(1)), int(m.group(2)), m.group(3)))
    return hits


def find_delta_samples(annotations, is_start, is_end):
    """Sample delta between the first is_start(...) match and the first
    is_end(...) match after it, or None if that pair isn't present.

    is_start=None means "start = capture start" (sample 0) rather than a
    text match - for checks with no bus-decodable start event, e.g. a
    power-on-to-ready delay where the operator power-cycles the device
    right as the capture begins (see conformance/environmental/
    aht21_conformance.py's poweron_ready check).

    is_end=None means "the check is one annotation's own (ss, es) span",
    not a delta between two different annotations - e.g. a decoder that
    already emits one timed annotation for the whole thing being measured
    (a NeoPixel transport decoder's single "reset" annotation spans the
    reset pulse itself; there's no separate start/end pair to look for)."""
    if is_end is None:
        for ss, es, text in annotations:
            if is_start(text):
                return es - ss
        return None
    if is_start is None:
        for ss, es, text in annotations:
            if is_end(text):
                return ss
        return None
    start = None
    for ss, es, text in annotations:
        if start is None:
            if is_start(text):
                start = es
            continue
        if is_end(text):
            return ss - start
    return None


def run_checks(chip_label, decoder_id, sigrok_channels, checks_table, trigger, timing_conf_path,
                sigrok_driver, sigrok_conn, protocol='i2c', extra_decoder_opts=''):
    """checks_table: {name: (is_start_fn, is_end_fn)}. sigrok_channels is the
    testconfig_wiring SIGROK_CHANNELS value, e.g. "D0=SCL,D1=SDA" (i2c),
    "D0=CLK,D1=MOSI,D2=MISO,D3=CS" (spi), or "D0=DIN" (neopixel). protocol
    names which bus decoder the chip decoder stacks on, and
    extra_decoder_opts passes any of that bus decoder's non-channel options -
    see decode_annotations(). decoder_id may be None for a chip whose own
    decoder consumes raw logic channels directly with no separate bus
    decoder to stack on (e.g. HX711 - see decode_annotations()). Prints
    PASS/FAIL/DONE, returns 0 if every check passed, else 1."""
    channel_names = channel_names_from_sigrok_channels(sigrok_channels)
    channel_map = channels_from_sigrok_channels(sigrok_channels)
    timing = parse_timing_conf(timing_conf_path)
    passed = failed = 0
    for name, (is_start, is_end) in checks_table.items():
        bounds = timing.get(name)
        if not bounds:
            print(f'FAIL {name}: no bounds in {timing_conf_path}')
            failed += 1
            continue
        samplerate = bounds.get('samplerate', '1m')
        capture_ms = bounds.get('capture_ms', '1000')

        with tempfile.NamedTemporaryFile(suffix='.sr', delete=False) as tmp:
            sr_file = tmp.name
        try:
            print(f'=== [conformance] {chip_label}: capturing "{name}" '
                  f'({capture_ms}ms @ {samplerate}) ===', file=sys.stderr)
            cap_thread = threading.Thread(
                target=capture,
                args=(sigrok_driver, sigrok_conn, channel_names, samplerate, capture_ms, sr_file),
            )
            cap_thread.start()
            trigger(name)
            cap_thread.join()

            annotations = decode_annotations(sr_file, decoder_id, channel_map, protocol=protocol,
                                              extra_decoder_opts=extra_decoder_opts)
            delta_samples = find_delta_samples(annotations, is_start, is_end)
            if delta_samples is None:
                print(f'FAIL {name}: start/end annotation pair not found in capture')
                failed += 1
                continue

            delta_s = delta_samples / _parse_samplerate(samplerate)

            ok = True
            detail = []
            if 'min' in bounds:
                detail.append(f'>= {bounds["min"][0]:g}{bounds["min"][1]}')
                ok = ok and delta_s >= _to_seconds(*bounds['min'])
            if 'max' in bounds:
                detail.append(f'<= {bounds["max"][0]:g}{bounds["max"][1]}')
                ok = ok and delta_s <= _to_seconds(*bounds['max'])
            bound_desc = ' and '.join(detail)

            if ok:
                print(f'PASS {name}: {delta_s * 1000:.2f}ms ({bound_desc})')
                passed += 1
            else:
                print(f'FAIL {name}: {delta_s * 1000:.2f}ms not {bound_desc}')
                failed += 1
        finally:
            try:
                os.unlink(sr_file)
            except OSError:
                pass

    print(f'===DONE: {passed} passed, {failed} failed===')
    return 0 if failed == 0 else 1


def channel_names_from_sigrok_channels(sigrok_channels):
    """"D0=SCL,D1=SDA" (testconfig_wiring's SIGROK_CHANNELS) is already valid
    sigrok-cli --channels rename syntax - physical channel D0 renamed to
    "SCL", D1 to "SDA" in the captured file - so it's passed straight
    through for the capture step."""
    return sigrok_channels


def channels_from_sigrok_channels(sigrok_channels):
    """"D0=SCL,D1=SDA" -> "scl=SCL:sda=SDA" (sigrok-cli's -P i2c:scl=..:sda=..
    role-mapping argument). Since the capture step (above) renames channels
    to their role names, the decode step's role mapping references those
    same role names, not the original physical channel names."""
    roles = [pair.split('=')[1].strip() for pair in sigrok_channels.split(',')]
    return ':'.join(f'{role.lower()}={role}' for role in roles)
