"""I²C bus auto-discovery for Linux hosts (specs/feature_i2c_discovery.md).

scan() enumerates the addresses that respond on a bus; discover() maps them to
the chips listed in the generated registry (discovery_registry.py, built from
registry/chips.json) and confirms a chip only when an identity-register read
matches exactly one candidate. Everything else is reported as candidates.

Linux only: needs smbus2 and /dev/i2c-N. MicroPython, CircuitPython and the
other firmware targets are out of scope.
"""

import errno
from dataclasses import dataclass, field

from .discovery_registry import CHIPS

FIRST_ADDRESS = 0x08
LAST_ADDRESS = 0x77

# i2cdetect-compatible policy: EEPROM-class ranges are probed with a read byte,
# because a quick write there can disturb the address pointer.
_READ_BYTE_RANGES = ((0x30, 0x37), (0x50, 0x5F))
_FUNC_SMBUS_QUICK = 0x00010000


@dataclass
class DiscoveredDevice:
    """One responding address (or merged alias block) and what it could be.

    Attributes:
        address: 7-bit address (lowest address of an alias block).
        candidates: Registry chip ids that could be at this address; [] when
            the device is present but unknown to the registry.
        identified: Chip id confirmed by an identity read, else None.
        driver: Driver name of the identified chip, if it has one.
        in_use_by_kernel: True when a kernel driver owns the address (EBUSY).
        probe_skipped_reason: "kernel_bound", "write_sensitive_candidate" or None.
        aliases: Other addresses merged into this device (24AA02UID), else [].
    """

    address: int
    candidates: list = field(default_factory=list)
    identified: str = None
    driver: str = None
    in_use_by_kernel: bool = False
    probe_skipped_reason: str = None
    aliases: list = field(default_factory=list)


def _open_bus(bus):
    if isinstance(bus, int):
        from smbus2 import SMBus
        return SMBus(bus), True
    return bus, False


def _uses_read_byte(addr):
    return any(lo <= addr <= hi for lo, hi in _READ_BYTE_RANGES)


def scan_detailed(bus, first=FIRST_ADDRESS, last=LAST_ADDRESS):
    """Scan a bus and report which responding addresses are kernel-bound.

    Args:
        bus: Bus number (int, opens /dev/i2c-N) or an open smbus2.SMBus.
        first: First address to probe (default 0x08).
        last: Last address to probe (default 0x77).

    Returns:
        dict: {address: in_use_by_kernel} for every address that responded.

    Raises:
        OSError: If every probed address failed with something other than a
            NACK (e.g. the adapter is broken), so an empty result would lie.
    """
    smbus, owned = _open_bus(bus)
    try:
        funcs = getattr(smbus, 'funcs', None)
        quick_ok = funcs is not None and bool(funcs & _FUNC_SMBUS_QUICK)
        found = {}
        errors = 0
        last_error = None
        for addr in range(first, last + 1):
            try:
                if quick_ok and not _uses_read_byte(addr):
                    smbus.write_quick(addr)
                else:
                    smbus.read_byte(addr)
                found[addr] = False
            except OSError as e:
                if e.errno == errno.EBUSY:
                    found[addr] = True
                elif e.errno not in (errno.ENXIO, errno.EREMOTEIO):
                    errors += 1
                    last_error = e
        if errors and errors == last - first + 1:
            raise last_error
        return found
    finally:
        if owned:
            smbus.close()


def scan(bus, first=FIRST_ADDRESS, last=LAST_ADDRESS):
    """Return the sorted 7-bit addresses that respond on the bus.

    Uses a quick write, or a read byte on 0x30-0x37 / 0x50-0x5F and on adapters
    without quick-write support. A kernel-bound address (EBUSY) counts as present.

    Args:
        bus: Bus number (int, opens /dev/i2c-N) or an open smbus2.SMBus.
        first: First address to probe (default 0x08).
        last: Last address to probe (default 0x77).

    Returns:
        list: Sorted responding addresses.

    Raises:
        OSError: On a bus-level failure (see scan_detailed).
    """
    return sorted(scan_detailed(bus, first, last))


def _read_identity(smbus, addr, probe, cache):
    key = (probe['register'], probe['reg_bytes'], probe['length'], probe['byte_order'])
    if key not in cache:
        from .connection.i2c_linux import I2CConnection
        try:
            conn = I2CConnection(smbus, addr, reg_bytes=probe['reg_bytes'])
            cache[key] = int.from_bytes(conn.read_reg(probe['register'], probe['length']), probe['byte_order'])
        except OSError:
            cache[key] = None
    return cache[key]


def _classify(smbus, addr, cands, in_use, active):
    ids = sorted(c['id'] for c in cands)
    if not cands:
        return DiscoveredDevice(addr, [], in_use_by_kernel=in_use)
    if in_use:
        return DiscoveredDevice(addr, ids, in_use_by_kernel=True, probe_skipped_reason='kernel_bound')
    probed = [c for c in cands if c['probe']]
    if not probed:
        return DiscoveredDevice(addr, ids)
    if not active and any(c['write_sensitive'] for c in cands):
        return DiscoveredDevice(addr, ids, probe_skipped_reason='write_sensitive_candidate')

    cache = {}
    matched = []
    for c in probed:
        value = _read_identity(smbus, addr, c['probe'], cache)
        key = (c['probe']['register'], c['probe']['reg_bytes'], c['probe']['length'], c['probe']['byte_order'])
        if cache[key] is not None and (value & c['probe']['mask']) in c['probe']['expected']:
            matched.append(c)
    if len(matched) == 1:
        chip = matched[0]
        return DiscoveredDevice(addr, [chip['id']], identified=chip['id'], driver=chip['driver'])
    if matched:
        return DiscoveredDevice(addr, sorted(c['id'] for c in matched))
    return DiscoveredDevice(addr, sorted(c['id'] for c in cands if not c['probe']))


def discover(bus, registry=None, active=False):
    """Scan the bus and name the chips that are connected.

    identified is set only when an identity-register read matches exactly one
    chip; otherwise candidates lists what the address could be. Addresses with
    a write-sensitive candidate are not probed unless active is True.

    Args:
        bus: Bus number (int, opens /dev/i2c-N) or an open smbus2.SMBus.
        registry: Chip table in the discovery_registry.CHIPS format (default: built in).
        active: Also run identity probes on addresses that have write-sensitive
            candidates (default False).

    Returns:
        list: DiscoveredDevice, sorted by address.

    Raises:
        OSError: On a bus-level failure.
    """
    chips = CHIPS if registry is None else registry
    smbus, owned = _open_bus(bus)
    try:
        present = scan_detailed(smbus)
        by_addr = {}
        for chip in chips:
            for a in chip['addresses']:
                by_addr.setdefault(a, []).append(chip)

        devices = []
        merged = set()
        for chip in chips:
            if not chip['aliased']:
                continue
            addrs = chip['addresses']
            if all(a in present for a in addrs):
                # A full block also fits any non-aliased chip that lists every address (24AA025UID).
                twins = [o['id'] for o in chips if o is not chip and not o['aliased'] and set(addrs) <= set(o['addresses'])]
                devices.append(DiscoveredDevice(
                    addrs[0], sorted([chip['id']] + twins), in_use_by_kernel=any(present[a] for a in addrs),
                    aliases=list(addrs[1:])))
                merged.update(addrs)

        for addr in sorted(present):
            if addr not in merged:
                devices.append(_classify(smbus, addr, by_addr.get(addr, []), present[addr], active))
        return sorted(devices, key=lambda d: d.address)
    finally:
        if owned:
            smbus.close()
