"""Conformance test for SPI fault injection (#767, spec §5) — no hardware.

SPIConnectionMock.fail_next_writes / fail_next_reads make transfers raise
OSError(EIO); a chip driver must let that surface instead of returning stale or
zero data.
"""

import errno
import sys

from periph.connection.spi_mock import SPIConnectionMock
from periph.chips.comms.rfm9x import RFM95Minimal

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


def injected(fn):
    """True if fn raises the mock's injected EIO (not some other OSError)."""
    try:
        fn()
    except OSError as e:
        return e.errno == errno.EIO and 'mock' in str(e)
    return False


# --- mock semantics (mirror I2CConnectionMock) ---
m = SPIConnectionMock()
m.set_register(0x10, [0xAB])
m.fail_next_writes(1, after=1)
check_true('write_read before the fault succeeds', m.write_read(b'\x10', 1) == b'\xab')
check_true('second write_read raises injected EIO', injected(lambda: m.write_read(b'\x10', 1)))
check_true('fault is one-shot', m.write_read(b'\x10', 1) == b'\xab')
check_true('failing call is still logged', len(m.writes) == 3)

m.fail_next_writes(1)
check_true('write raises injected EIO', injected(lambda: m.write(b'\x20\x01')))
check_true('failed write does not update the register map', 0x20 not in m.registers)

m.queue_read(b'\x55')
m.fail_next_reads(1)
check_true('read raises injected EIO', injected(lambda: m.read(1)))
check_true('failed read does not consume the queue', m.read(1) == b'\x55')

# --- driver surfaces the fault ---
conn = SPIConnectionMock()
conn.set_register(0x42, [0x12])  # REG_VERSION = expected 0x12
conn.fail_next_writes(1)         # the version read in __init__ fails
check_true('RFM95Minimal init surfaces an SPI fault as OSError(EIO)',
           injected(lambda: RFM95Minimal(conn, 915000000)))

conn = SPIConnectionMock()
conn.set_register(0x42, [0x12])
RFM95Minimal(conn, 915000000)    # healthy init still works afterwards
check_true('RFM95Minimal init succeeds without a fault', True)

print('%d passed, %d failed' % (passed, failed))
sys.exit(1 if failed else 0)
