import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.io_expander.pcf8575 import Pcf8575Full

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


# PCF8575 has no sub-registers: every transaction is a plain 2-byte
# read()/write() (Port 0 first, Port 1 second; no register pointer), so
# I2CConnectionMock's register map is never consulted — reads must be
# preloaded via queue_read() in the exact order the driver will issue
# them. Pcf8575Full's constructor issues one extra 2-byte read (to seed
# `_prev` for interrupt comparison) right after Pcf8575Minimal's init
# write, so it must be queued too.
connection = I2CConnectionMock()
connection.queue_read([0xFF, 0xFF])  # Full.__init__ seeds `_prev`
chip = Pcf8575Full(connection)
check_true('init', True)

# Construction writes [0xFF, 0xFF] (all 16 pins to quasi-bidirectional input).
check_true('init_writes_ff_ff', connection.writes[0] == bytes([0xFF, 0xFF]))
check_true('init_shadow', chip._shadow == [0xFF, 0xFF])

# read_port(0)/(1): both derived from one 2-byte read.
connection.queue_read([0x5A, 0xA5])
check_true('read_port_0', chip.read_port(0) == 0x5A)
connection.queue_read([0x5A, 0xA5])
check_true('read_port_1', chip.read_port(1) == 0xA5)

# write_port(): writes both shadow bytes, preserving the untouched port.
chip.write_port(0, 0x3C)
check_true('write_port_0', connection.writes[-1] == bytes([0x3C, 0xFF]))
chip.write_port(1, 0x0F)
check_true('write_port_1_preserves_port0', connection.writes[-1] == bytes([0x3C, 0x0F]))

# pin() read on Port 0 and Port 1.
pin3 = chip.pin(3)  # Port 0, bit 3
connection.queue_read([0x08, 0x00])
check_true('pin_read_port0', pin3.value() == 1)

pin11 = chip.pin(11)  # Port 1, bit 3
connection.queue_read([0x00, 0x08])
check_true('pin_read_port1', pin11.value() == 1)

# Pin set high/low preserves other shadow bits within the same port.
chip.write_port(0, 0xFF)
chip.write_port(1, 0xFF)
pin3.off()
check_true('pin3_off', connection.writes[-1] == bytes([0xFF & ~0x08, 0xFF]))
pin5 = chip.pin(5)
pin5.off()
check_true('pin5_off_preserves_pin3', connection.writes[-1] == bytes([0xFF & ~0x08 & ~0x20, 0xFF]))
pin11.off()
check_true('pin11_off_only_touches_port1', connection.writes[-1] == bytes([0xFF & ~0x08 & ~0x20, 0xFF & ~0x08]))

# Toggle.
pin3.toggle()
check_true('pin3_toggle_on', connection.writes[-1][0] & 0x08 != 0)
pin3.toggle()
check_true('pin3_toggle_off', connection.writes[-1][0] & 0x08 == 0)

# init(IN)/init(OUT) sets the pin high (input/quasi-bidirectional) or low.
pin0 = chip.pin(0)
pin0.init(Pcf8575Full.OUT)
check_true('pin_init_out_drives_low', (connection.writes[-1][0] & 0x01) == 0)
pin0.init(Pcf8575Full.IN)
check_true('pin_init_in_releases_high', (connection.writes[-1][0] & 0x01) == 1)

# Full: poll_interrupt() compares to the previous 2-byte read and returns
# the 16-bit changed-pin bitmask (bits 0-7 = Port 0, bits 8-15 = Port 1).
chip._prev = [0xFF, 0xFF]
connection.queue_read([0xF7, 0xFE])  # Port0 bit3 low, Port1 bit0 low
check_true('poll_interrupt_detects_change', chip.poll_interrupt() == (0x08 | (0x01 << 8)))
connection.queue_read([0xF7, 0xFE])  # no further change
check_true('poll_interrupt_no_change', chip.poll_interrupt() == 0x00)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
