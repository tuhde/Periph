import errno
import sys

from periph import discovery
from periph.discovery import scan, scan_detailed, discover
from periph.discovery_registry import CHIPS

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


class FakeBus:
    """Fake smbus2.SMBus. devices maps 7-bit address -> {register: byte}; missing registers read 0xFF."""

    def __init__(self, devices=None, busy=(), quick=True, broken=False, read_error=False):
        self.funcs = 0x00010000 if quick else 0
        self.devices = devices or {}
        self.busy = set(busy)
        self.broken = broken
        self.read_error = read_error
        self.calls = []   # (kind, addr) for scan probes
        self.writes = []  # (addr, bytes) for register-pointer writes

    def _probe(self, kind, addr):
        self.calls.append((kind, addr))
        if self.broken:
            raise OSError(errno.EIO, 'bus error')
        if addr in self.busy:
            raise OSError(errno.EBUSY, 'busy')
        if addr not in self.devices:
            raise OSError(errno.ENXIO, 'no ack')

    def write_quick(self, addr):
        self._probe('quick', addr)

    def read_byte(self, addr):
        self._probe('read', addr)
        return 0

    def i2c_rdwr(self, write_msg, read_msg):
        if self.read_error:
            raise OSError(errno.EIO, 'io error')
        data = bytes(write_msg)
        self.writes.append((write_msg.addr, data))
        if write_msg.addr not in self.devices:
            raise OSError(errno.ENXIO, 'no ack')
        reg = int.from_bytes(data, 'big')
        regs = self.devices[write_msg.addr]
        for i in range(read_msg.len):
            read_msg.buf[i] = regs.get(reg + i, 0xFF)


def device(**byte_at):
    return {int(k[1:], 16): v for k, v in byte_at.items()}


def by_id(devs):
    return {d.address: d for d in devs}


# --- scan: method per address ---
bus = FakeBus({0x76: {}, 0x50: {}, 0x1B: {}})
check_true('scan_finds_all', scan(bus) == [0x1B, 0x50, 0x76])
kinds = {a: k for k, a in bus.calls}
check_true('scan_quick_write_default', kinds[0x76] == 'quick' and kinds[0x08] == 'quick')
check_true('scan_read_byte_eeprom_ranges', kinds[0x50] == 'read' and kinds[0x30] == 'read' and kinds[0x5F] == 'read')
check_true('scan_skips_reserved', min(a for _, a in bus.calls) == 0x08 and max(a for _, a in bus.calls) == 0x77)

bus = FakeBus({0x76: {}}, quick=False)
scan(bus)
check_true('scan_fallback_read_byte_without_quick', all(k == 'read' for k, _ in bus.calls))

bus = FakeBus({0x40: {}}, busy=[0x42])
check_true('scan_ebusy_is_present', scan_detailed(bus) == {0x40: False, 0x42: True})

try:
    scan(FakeBus(broken=True))
    check_true('scan_raises_on_bus_failure', False)
except OSError:
    check_true('scan_raises_on_bus_failure', True)

check_true('scan_empty_bus', scan(FakeBus()) == [])

# --- discover: identity probes ---
bme = device(**{'x0xD0': 0x60})
devs = by_id(discover(FakeBus({0x76: bme})))
check_true('bme280_identified', devs[0x76].identified == 'bme280' and devs[0x76].candidates == ['bme280'])
check_true('bme280_driver', devs[0x76].driver == 'bme280')

for chip_id, reg, value, addr in [('bmp280', 0xD0, 0x58, 0x76), ('bme680', 0xD0, 0x61, 0x77),
                                   ('bmp384', 0x00, 0x50, 0x76), ('mpu6050', 0x75, 0x68, 0x68),
                                   ('mpu9250', 0x75, 0x71, 0x68), ('mpu9255', 0x75, 0x73, 0x69),
                                   ('l3g4200d', 0x0F, 0xD3, 0x68), ('lps33hw', 0x0F, 0xB1, 0x5C),
                                   ('adxl345', 0x00, 0xE5, 0x53), ('vl53l0x', 0xC0, 0xEE, 0x29),
                                   ('mfrc522', 0x37, 0x92, 0x28)]:
    d = discover(FakeBus({addr: {reg: value}}))[0]
    check_true('identify_' + chip_id, d.identified == chip_id)

# multi-byte identity registers
d = discover(FakeBus({0x52: {0x00: 0x60, 0x01: 0x01}}))[0]
check_true('identify_ens160_little_endian', d.identified == 'ens160')
d = discover(FakeBus({0x40: {0xFF: 0x22, 0x100: 0x60}}))[0]
check_true('identify_ina226_die_id', d.identified == 'ina226')
d = discover(FakeBus({0x40: {0xFF: 0x32, 0x100: 0x20}}))[0]
check_true('identify_ina3221_die_id', d.identified == 'ina3221')
bus = FakeBus({0x48: {0x0F: 0x01, 0x10: 0x17}})
d = discover(bus)[0]
check_true('tmp117_skipped_pcf8591_write_sensitive', d.identified is None and d.probe_skipped_reason == 'write_sensitive_candidate' and not bus.writes)
d = discover(FakeBus({0x48: {0x0F: 0x01, 0x10: 0x17}}), active=True)[0]
check_true('identify_tmp117_masked', d.identified == 'tmp117')
for chip_id, value in [('apds9960', 0xAB), ('apds-9930', 0x39)]:
    d = discover(FakeBus({0x39: {0x92: value}}), active=True)[0]
    check_true('identify_' + chip_id + '_active', d.identified == chip_id)
d = discover(FakeBus({0x39: {0x92: 0xAB}}))[0]
check_true('apds_skipped_without_active', d.identified is None and d.probe_skipped_reason == 'write_sensitive_candidate')
d = discover(FakeBus({0x18: {0x07: 0x04, 0x08: 0x01}}))[0]
check_true('identify_mcp9808_masked', d.identified == 'mcp9808')
bus = FakeBus({0x29: {0x010F: 0xEA, 0x0110: 0xCC}})
d = discover(bus)[0]
check_true('vl53l1x_register_sent_as_two_bytes', (0x29, bytes([0x01, 0x0F])) in bus.writes)
check_true('identify_vl53l1x_two_byte_register', d.identified == 'vl53l1x')
d = discover(FakeBus({0x1E: {0x0A: 0x48, 0x0B: 0x34, 0x0C: 0x33}}))[0]
check_true('hmc5883l_unique_address_with_id', d.identified == 'hmc5883l')

# --- ambiguity is a final answer ---
d = discover(FakeBus({0x5C: {0x0F: 0xB4}}))[0]
check_true('lps22df_lps28dfw_ambiguous', d.identified is None and d.candidates == ['lps22df', 'lps28dfw'])
d = discover(FakeBus({0x77: {0xD0: 0x55}}))[0]
check_true('bmp085_bmp180_ambiguous', d.identified is None and d.candidates == ['bmp085', 'bmp180'])

# --- no ID match falls back to ID-less candidates ---
d = discover(FakeBus({0x68: {}}))[0]
check_true('ds3231_pcf8523_remain', d.identified is None and d.candidates == ['drv8830', 'ds3231', 'pcf8523'])
d = discover(FakeBus({0x40: {}}))[0]
check_true('ina219_by_elimination', d.identified is None and d.candidates == ['ina219'])
d = discover(FakeBus({0x36: {}}))[0]
check_true('as5600_sole_candidate_unconfirmed', d.identified is None and d.candidates == ['as5600'])
d = discover(FakeBus({0x1B: {}}))[0]
check_true('unknown_device', d.candidates == [] and d.identified is None)

# --- write-sensitive gating ---
bus = FakeBus({0x38: {}})
d = discover(bus)[0]
check_true('aht21_cands', d.candidates == ['ade7953', 'aht21', 'pcf8574', 'pcf8576'])
check_true('no_probe_when_nothing_to_probe', d.probe_skipped_reason is None and not bus.writes)

custom = [
    {'id': 'pcf-like', 'driver': None, 'write_sensitive': True, 'aliased': False, 'addresses': (0x20,), 'probe': None},
    {'id': 'idchip', 'driver': None, 'write_sensitive': False, 'aliased': False, 'addresses': (0x20,),
     'probe': {'register': 0x10, 'reg_bytes': 1, 'length': 1, 'byte_order': 'big', 'mask': 0xFF, 'expected': (0x42,)}},
]
bus = FakeBus({0x20: {0x10: 0x42}})
d = discover(bus, registry=custom)[0]
check_true('write_sensitive_skips_probe', d.probe_skipped_reason == 'write_sensitive_candidate' and not bus.writes
           and d.candidates == ['idchip', 'pcf-like'] and d.identified is None)
bus = FakeBus({0x20: {0x10: 0x42}})
d = discover(bus, registry=custom, active=True)[0]
check_true('active_probes_anyway', d.identified == 'idchip' and bus.writes)

# --- kernel-bound ---
bus = FakeBus({0x76: bme}, busy=[0x77])
d = by_id(discover(bus))
check_true('kernel_bound_reported', d[0x77].in_use_by_kernel and d[0x77].probe_skipped_reason == 'kernel_bound'
           and d[0x77].identified is None)
check_true('kernel_bound_not_probed', all(a != 0x77 for a, _ in bus.writes))

# --- failed identity read ---
d = discover(FakeBus({0x76: bme}, read_error=True))[0]
check_true('failed_read_is_no_match', d.identified is None and d.candidates == [])

# --- aliased 24AA02UID ---
bus = FakeBus({a: {} for a in range(0x50, 0x58)})
devs = discover(bus)
check_true('alias_block_merged', len(devs) == 1 and devs[0].address == 0x50 and devs[0].candidates == ['24aa02uid']
           and devs[0].aliases == list(range(0x51, 0x58)))
bus = FakeBus({0x50: {}, 0x51: {}})
devs = discover(bus)
check_true('partial_alias_reported_individually', [d.address for d in devs] == [0x50, 0x51] and all(not d.aliases for d in devs))

# --- registry sanity ---
check_true('registry_nonempty', len(CHIPS) >= 45)
check_true('registry_ids_unique', len({c['id'] for c in CHIPS}) == len(CHIPS))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
