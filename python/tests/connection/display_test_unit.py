"""Unit test for the display connection, SoftSPI and the Display mock — no hardware."""

import sys
import time

from periph.connection import display_linux
from periph.connection.display_base import WireMode
from periph.connection.display_circuitpython import DisplayConnection as CPConnection
from periph.connection.display_linux import DisplayConnection as LinuxConnection
from periph.connection.display_micropython import DisplayConnection as MPConnection
from periph.connection.display_mock import DisplayConnectionMock
from periph.connection.input_pin import InputPin
from periph.connection.output_pin import OutputPin
from periph.connection.softspi import SoftSPI

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


def check_raises(label, exc, fn):
    try:
        fn()
    except exc:
        check_true(label, True)
    except Exception as e:  # noqa: BLE001
        check_true('%s (raised %r)' % (label, e), False)
    else:
        check_true('%s (nothing raised)' % label, False)


class Log:
    def __init__(self):
        self.events = []

    def add(self, *e):
        self.events.append(e)


class FakeOut(OutputPin):
    def __init__(self, name, log):
        self.name = name
        self.log = log
        self.level = None

    def set(self, high):
        self.level = bool(high)
        self.log.add(self.name, self.level)


class FakeIn(InputPin):
    """Level script: successive is_high() calls return the script entries; the last repeats."""

    def __init__(self, script):
        self.script = list(script)
        self.polls = 0

    def is_high(self):
        v = self.script[min(self.polls, len(self.script) - 1)]
        self.polls += 1
        return v

    def on_edge(self, handler, trigger=InputPin.FALLING):
        pass

    def off_edge(self, handler):
        pass


class FakeSPI:
    """machine.SPI-like: write(data), read(n); records into the shared log."""

    def __init__(self, log, read_data=b''):
        self.log = log
        self.read_data = bytearray(read_data)

    def write(self, data):
        self.log.add('spi.write', bytes(data))

    def read(self, n, write=0x00):
        self.log.add('spi.read', n)
        out = bytes(self.read_data[:n]).ljust(n, b'\x00')
        del self.read_data[:n]
        return out


def make_mp(wire_mode=WireMode.SIMPLEX, busy_script=(False,), read_data=b'', **kw):
    log = Log()
    spi = FakeSPI(log, read_data)
    cs, dc, res = FakeOut('cs', log), FakeOut('dc', log), FakeOut('res', log)
    busy = FakeIn(busy_script)
    conn = MPConnection(spi, cs, dc, res, busy, wire_mode=wire_mode, **kw)
    return conn, log, busy


# ---- MicroPython connection: command/data framing and CS windows -------------------
conn, log, _ = make_mp()
log.events.clear()
conn.send_command(0x12, b'\x01\x02')
check_true('send_command: dc low, CS window around command, dc high, CS window around params',
           log.events == [('dc', False), ('cs', False), ('spi.write', b'\x12'), ('cs', True),
                          ('dc', True), ('cs', False), ('spi.write', b'\x01\x02'), ('cs', True)])
log.events.clear()
conn.send_command(0x04)
check_true('send_command without data is one command transfer',
           log.events == [('dc', False), ('cs', False), ('spi.write', b'\x04'), ('cs', True)])
log.events.clear()
conn.send_data(bytes([0xFF] * 4))
check_true('send_data: dc high, one CS window',
           log.events == [('dc', True), ('cs', False), ('spi.write', b'\xff\xff\xff\xff'), ('cs', True)])
check_true('CS idles high after construction', conn._cs.level is True)

# ---- reset ---------------------------------------------------------------------------
conn, log, _ = make_mp(reset_pulse_us=500, reset_settle_ms=2)
log.events.clear()
t0 = time.monotonic()
conn.reset()
dt = time.monotonic() - t0
check_true('reset: RES low then high', [e for e in log.events if e[0] == 'res'] == [('res', False), ('res', True)])
check_true('reset waits at least pulse + settle', dt >= 0.0025)

# ---- wait_busy / busy_level ------------------------------------------------------------
conn, log, busy = make_mp(busy_script=(False, False, True))      # BUSY_N: low = busy
check_true('wait_busy active-low returns True once BUSY goes high',
           conn.wait_busy(False, timeout_ms=1000) is True and busy.polls == 3)
conn, log, busy = make_mp(busy_script=(True, True, False))       # BUSY: high = busy
check_true('wait_busy active-high returns True once BUSY goes low',
           conn.wait_busy(True, timeout_ms=1000) is True and busy.polls == 3)
conn, log, busy = make_mp(busy_script=(False,))
t0 = time.monotonic()
ok = conn.wait_busy(False, timeout_ms=30)
check_true('wait_busy times out when BUSY stays active (ms tick granularity)',
           ok is False and time.monotonic() - t0 >= 0.029)
conn, log, busy = make_mp(busy_script=(True,))
check_true('wait_busy returns immediately when already ready', conn.wait_busy(False, timeout_ms=1000) is True)
check_true('busy_level returns the raw level', conn.busy_level() is True)

# ---- read paths -----------------------------------------------------------------------
conn, log, _ = make_mp()
check_raises('read_data in SIMPLEX raises', RuntimeError, lambda: conn.read_data(1))

conn, log, _ = make_mp(wire_mode=WireMode.HALF_DUPLEX_PIN, read_data=b'\x13\x07')
log.events.clear()
out = conn.read_data(2)
check_true('read_data returns the bytes', out == b'\x13\x07')
check_true('read_data: dc high, one CS window per byte',
           log.events == [('dc', True), ('cs', False), ('spi.read', 1), ('cs', True),
                          ('cs', False), ('spi.read', 1), ('cs', True)])

check_raises('MicroPython rejects HALF_DUPLEX_HW', ValueError,
             lambda: make_mp(wire_mode=WireMode.HALF_DUPLEX_HW))


class WriteOnlySPI:
    def write(self, data):
        pass


check_raises('HALF_DUPLEX_PIN needs an SPI with read()', ValueError,
             lambda: MPConnection(WriteOnlySPI(), FakeOut('cs', Log()), FakeOut('dc', Log()),
                                  FakeOut('res', Log()), FakeIn([False]),
                                  wire_mode=WireMode.HALF_DUPLEX_PIN))
check_raises('unknown wire_mode rejected', ValueError, lambda: make_mp(wire_mode=9))

# ---- enable / disable ------------------------------------------------------------------
log = Log()
en = FakeOut('en', log)
conn = MPConnection(FakeSPI(log), FakeOut('cs', log), FakeOut('dc', log), FakeOut('res', log),
                    FakeIn([False]), wire_mode=WireMode.HALF_DUPLEX_PIN, en_pin=en,
                    en_active_high=False)
conn.disable()
check_true('disable drives an active-low EN pin high', en.level is True and not conn.is_enabled())
log.events.clear()
conn.send_command(0x12)
conn.send_data(b'\x00')
conn.reset()
check_true('disabled connection is a no-op on the bus', log.events == [])
check_true('disabled read_data returns zeros', conn.read_data(3) == b'\x00\x00\x00')
conn.enable()
check_true('enable drives an active-low EN pin low', en.level is False and conn.is_enabled())

# ---- optional RES / BUSY (OLED: no BUSY line, RES tied high) -------------------------
log = Log()
oled = MPConnection(FakeSPI(log), FakeOut('cs', log), FakeOut('dc', log), None, None)
log.events.clear()
oled.reset()
check_true('reset() without a RES pin is a no-op', log.events == [])
check_true('wait_busy() without a BUSY pin returns True at once', oled.wait_busy(True, timeout_ms=10) is True)
check_raises('busy_level() without a BUSY pin raises', RuntimeError, lambda: oled.busy_level())
oled.send_command(0xAF)
oled.send_data(b'\x01\x02')
check_true('OLED command/data framing still works',
           log.events == [('dc', False), ('cs', False), ('spi.write', b'\xaf'), ('cs', True),
                          ('dc', True), ('cs', False), ('spi.write', b'\x01\x02'), ('cs', True)])

# ---- SoftSPI ---------------------------------------------------------------------------


class WireSim:
    """SCK/MOSI recorder and MISO feeder: samples MOSI on SCK rising edges."""

    def __init__(self, miso_bits=()):
        self.sck_level = False
        self.mosi_level = False
        self.sampled = []
        self.miso_bits = list(miso_bits)
        self.miso_index = 0
        self.rising = 0

    def sck(self):
        sim = self

        class P(OutputPin):
            def set(self, high):
                if high and not sim.sck_level:
                    sim.sampled.append(sim.mosi_level)
                    sim.rising += 1
                sim.sck_level = bool(high)
        return P()

    def mosi(self):
        sim = self

        class P(OutputPin):
            def set(self, high):
                sim.mosi_level = bool(high)
        return P()

    def miso(self):
        sim = self

        class P(InputPin):
            def is_high(self):
                v = sim.miso_bits[sim.miso_index]
                sim.miso_index += 1
                return bool(v)

            def on_edge(self, handler, trigger=InputPin.FALLING):
                pass

            def off_edge(self, handler):
                pass
        return P()


sim = WireSim()
spi = SoftSPI(sim.sck(), sim.mosi())
spi.write(bytes([0xA5, 0x3C]))
bits = [int(b) for b in sim.sampled]
check_true('SoftSPI write: MSB-first, sampled on rising edges',
           bits == [1, 0, 1, 0, 0, 1, 0, 1] + [0, 0, 1, 1, 1, 1, 0, 0])
check_true('SoftSPI idles with SCK low', sim.sck_level is False)

sim = WireSim(miso_bits=[0, 0, 0, 1, 0, 0, 1, 1,  1, 1, 1, 1, 1, 1, 1, 1])
spi = SoftSPI(sim.sck(), sim.mosi(), sim.miso())
check_true('SoftSPI read: MSB-first from MISO', spi.read(2) == bytes([0x13, 0xFF]))
check_true('SoftSPI read drives the write byte on MOSI', sim.rising == 16)
check_raises('SoftSPI read without MISO raises', RuntimeError,
             lambda: SoftSPI(WireSim().sck(), WireSim().mosi()).read(1))

# ---- Linux connection: software SPI --------------------------------------------------
log = Log()
soft = FakeSPI(log, b'\x2A')
lc = LinuxConnection(FakeOut('dc', log), FakeOut('res', log), FakeIn([False]),
                     wire_mode=WireMode.HALF_DUPLEX_PIN, spi=soft, cs=FakeOut('cs', log))
log.events.clear()
lc.send_command(0x40)
check_true('Linux software SPI: command transfer framed by CS',
           log.events == [('dc', False), ('cs', False), ('spi.write', b'\x40'), ('cs', True)])
check_true('Linux software SPI: read_data', lc.read_data(1) == b'\x2A')
check_raises('Linux: neither hardware nor software given', ValueError,
             lambda: LinuxConnection(FakeOut('dc', Log()), FakeOut('res', Log()), FakeIn([False])))
check_raises('Linux: both hardware and software given', ValueError,
             lambda: LinuxConnection(FakeOut('dc', Log()), FakeOut('res', Log()), FakeIn([False]),
                                     bus_num=0, device_num=0, spi=FakeSPI(Log()), cs=FakeOut('cs', Log())))
check_raises('Linux: software SPI needs cs', ValueError,
             lambda: LinuxConnection(FakeOut('dc', Log()), FakeOut('res', Log()), FakeIn([False]),
                                     spi=FakeSPI(Log())))
check_raises('Linux: HALF_DUPLEX_HW needs hardware SPI', ValueError,
             lambda: LinuxConnection(FakeOut('dc', Log()), FakeOut('res', Log()), FakeIn([False]),
                                     wire_mode=WireMode.HALF_DUPLEX_HW, spi=FakeSPI(Log()),
                                     cs=FakeOut('cs', Log())))

# ---- Linux connection: hardware SPI through a fake spidev ------------------------------


class FakeSpiDev:
    instances = []

    def __init__(self):
        self.calls = []
        self.mode = None
        self.max_speed_hz = None
        self.threewire = False
        self.opened = None
        self.closed = False
        FakeSpiDev.instances.append(self)

    def open(self, bus, dev):
        self.opened = (bus, dev)

    def writebytes2(self, data):
        self.calls.append(('writebytes2', bytes(data)))

    def readbytes(self, n):
        self.calls.append(('readbytes', n))
        return [0x11] * n

    def xfer2(self, data):
        self.calls.append(('xfer2', list(data)))
        return [0x22] * len(data)

    def close(self):
        self.closed = True


class FakeSpidevModule:
    SpiDev = FakeSpiDev


saved = display_linux.spidev
display_linux.spidev = FakeSpidevModule
try:
    hw = LinuxConnection(FakeOut('dc', Log()), FakeOut('res', Log()), FakeIn([False]),
                         wire_mode=WireMode.HALF_DUPLEX_HW, bus_num=1, device_num=0,
                         max_speed_hz=2000000)
    dev = FakeSpiDev.instances[-1]
    check_true('hardware: opens bus/device, mode 0, speed',
               dev.opened == (1, 0) and dev.mode == 0 and dev.max_speed_hz == 2000000)
    check_true('hardware HALF_DUPLEX_HW enables spidev 3-wire', dev.threewire is True)
    hw.send_command(0x12, b'\x05')
    check_true('hardware writes use writebytes2',
               dev.calls == [('writebytes2', b'\x12'), ('writebytes2', b'\x05')])
    dev.calls.clear()
    check_true('hardware 3-wire read uses readbytes, one byte per window',
               hw.read_data(2) == b'\x11\x11' and dev.calls == [('readbytes', 1), ('readbytes', 1)])
    hw.close()
    check_true('close releases the spidev device', dev.closed)

    hp = LinuxConnection(FakeOut('dc', Log()), FakeOut('res', Log()), FakeIn([False]),
                         wire_mode=WireMode.HALF_DUPLEX_PIN, bus_num=0, device_num=1)
    dev = FakeSpiDev.instances[-1]
    check_true('HALF_DUPLEX_PIN keeps spidev 4-wire', dev.threewire is False)
    check_true('hardware HALF_DUPLEX_PIN read uses full-duplex xfer2',
               hp.read_data(1) == b'\x22' and dev.calls == [('xfer2', [0])])
    simple = LinuxConnection(FakeOut('dc', Log()), FakeOut('res', Log()), FakeIn([False]),
                             bus_num=0, device_num=0)
    check_raises('SIMPLEX read raises on Linux hardware', RuntimeError, lambda: simple.read_data(1))
finally:
    display_linux.spidev = saved

# ---- CircuitPython connection ---------------------------------------------------------


class FakeBusio:
    def __init__(self, log):
        self.log = log
        self.locked = False

    def try_lock(self):
        self.locked = True
        self.log.add('lock')
        return True

    def configure(self, baudrate, polarity, phase):
        self.log.add('configure', baudrate, polarity, phase)

    def write(self, data):
        self.log.add('write', bytes(data))

    def readinto(self, buf):
        self.log.add('readinto', len(buf))
        for i in range(len(buf)):
            buf[i] = 0x5A

    def unlock(self):
        self.locked = False
        self.log.add('unlock')


log = Log()
cp = CPConnection(FakeBusio(log), FakeOut('cs', log), FakeOut('dc', log), FakeOut('res', log),
                  FakeIn([False]), baudrate=1000000, wire_mode=WireMode.HALF_DUPLEX_PIN)
log.events.clear()
cp.send_command(0x10, b'\x01')
check_true('CircuitPython locks/configures mode 0 around each transfer',
           log.events == [('dc', False), ('lock',), ('configure', 1000000, 0, 0), ('cs', False),
                          ('write', b'\x10'), ('cs', True), ('unlock',),
                          ('dc', True), ('lock',), ('configure', 1000000, 0, 0), ('cs', False),
                          ('write', b'\x01'), ('cs', True), ('unlock',)])
check_true('CircuitPython read_data', cp.read_data(2) == b'\x5A\x5A')
check_raises('CircuitPython rejects HALF_DUPLEX_HW', ValueError,
             lambda: CPConnection(FakeBusio(Log()), FakeOut('cs', Log()), FakeOut('dc', Log()),
                                  FakeOut('res', Log()), FakeIn([False]),
                                  wire_mode=WireMode.HALF_DUPLEX_HW))

# ---- mock ------------------------------------------------------------------------------
m = DisplayConnectionMock(wire_mode=WireMode.HALF_DUPLEX_PIN)
m.reset()
m.send_command(0x04)
m.wait_busy(False, 100)
m.send_command(0x10, b'\xff')
m.send_data(b'\x00\x01')
m.queue_read(b'\x13')
check_true('mock reads queued bytes then zero-pads', m.read_data(2) == b'\x13\x00')
check_true('mock records events in order',
           m.events == [('reset',), ('command', 0x04, b''), ('wait_busy', False, 100),
                        ('command', 0x10, b'\xff'), ('data', b'\x00\x01'), ('read', 2)])
check_true('mock commands/reset_count helpers', m.commands == [(0x04, b''), (0x10, b'\xff')] and m.reset_count == 1)
m.ready = False
check_true('mock wait_busy honours ready=False', m.wait_busy(True) is False)
check_raises('mock SIMPLEX read raises', RuntimeError, lambda: DisplayConnectionMock().read_data(1))

print('%d passed, %d failed' % (passed, failed))
sys.exit(1 if failed else 0)
