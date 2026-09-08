import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.io_expander.pcf8574 import Pcf8574Full

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


# PCF8574 has no sub-registers: every transaction is a single plain byte
# read()/write() (no register pointer), so I2CConnectionMock's register map
# is never consulted — reads must be preloaded via queue_read() in the
# exact order the driver will issue them. Pcf8574Full's constructor issues
# one extra read (to seed `prev` for interrupt comparison) right after
# Pcf8574Minimal's init write, so it must be queued too.
connection = I2CConnectionMock()
connection.queue_read([0xFF])  # Full.__init__ seeds `prev` via read_port_raw()
chip = Pcf8574Full(connection)
check_true('init', True)

# Construction writes 0xFF (all pins to quasi-bidirectional input mode).
check_true('init_writes_0xff', connection.writes[0] == bytes([0xFF]))
check_true('init_shadow', chip._shadow == 0xFF)

# read_port(): plain single-byte read.
connection.queue_read([0x5A])
check_true('read_port', chip.read_port() == 0x5A)

# write_port(): plain single-byte write; updates shadow.
chip.write_port(0, 0x3C)
check_true('write_port', connection.writes[-1] == bytes([0x3C]))
check_true('write_port_shadow', chip._shadow == 0x3C)

# pin().value() reads the live bus level (not the shadow).
pin3 = chip.pin(3)
connection.queue_read([0x08])  # bit 3 high
check_true('pin_read', pin3.value() == 1)

# Pin set high/low preserves other shadow bits (read-modify-write).
chip.write_port(0, 0xFF)
pin3.off()
check_true('pin3_off', connection.writes[-1] == bytes([0xFF & ~0x08]))
pin5 = chip.pin(5)
pin5.off()
check_true('pin5_off_preserves_pin3', connection.writes[-1] == bytes([0xFF & ~0x08 & ~0x20]))
pin3.on()
check_true('pin3_on_preserves_pin5', connection.writes[-1] == bytes([0xFF & ~0x20]))

# Toggle.
pin3.toggle()
check_true('pin3_toggle_off', connection.writes[-1] == bytes([0xFF & ~0x20 & ~0x08]))
pin3.toggle()
check_true('pin3_toggle_on', connection.writes[-1] == bytes([0xFF & ~0x20]))

# init(IN)/init(OUT) sets the pin high (input/quasi-bidirectional) or low.
pin0 = chip.pin(0)
pin0.init(Pcf8574Full.OUT)
check_true('pin_init_out_drives_low', (connection.writes[-1][0] & 0x01) == 0)
pin0.init(Pcf8574Full.IN)
check_true('pin_init_in_releases_high', (connection.writes[-1][0] & 0x01) == 1)

# Full: poll_interrupt() compares to the previous read and returns the
# changed-pin bitmask, also updating the stored previous value.
chip._prev = 0xFF
connection.queue_read([0xF7])  # bit 3 now low
check_true('poll_interrupt_detects_change', chip.poll_interrupt() == 0x08)
connection.queue_read([0xF7])  # no further change
check_true('poll_interrupt_no_change', chip.poll_interrupt() == 0x00)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
