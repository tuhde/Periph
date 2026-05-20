"""PCF8574 hardware test — Linux (smbus2 / /dev/i2c-N).

Uses environment variables:
    I2C_BUS  (default 1)
    I2C_ADDR (default 0x20)
"""
import os
from periph.transport.i2c_linux import I2CTransport
from periph.chips.io_expander.pcf8574 import Pcf8574Minimal, Pcf8574Full

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


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {} expected {}'.format(label, got, expected))
        failed += 1


bus  = int(os.environ.get('I2C_BUS',  '1'))
addr = int(os.environ.get('I2C_ADDR', '0x20'), 16)

transport = I2CTransport(bus, addr)
chip = Pcf8574Minimal(transport)

check_eq('init_shadow', chip._shadow, 0xFF)

port = chip.read_port()
check_true('read_port_range', 0 <= port <= 0xFF)

chip.write_port(mask=0xAA)
check_eq('write_port_shadow', chip._shadow, 0xAA)
chip.write_port(mask=0xFF)

p0 = chip.pin(0)
p0.init(Pcf8574Minimal.OUT)
p0.off()
check_eq('pin_off_shadow', chip._shadow & 0x01, 0)

p0.on()
check_eq('pin_on_shadow', chip._shadow & 0x01, 1)

p0.toggle()
check_eq('pin_toggle_shadow', chip._shadow & 0x01, 0)
p0.on()

p0.value(0)
check_eq('value_write0_shadow', chip._shadow & 0x01, 0)
p0.value(1)
check_eq('value_write1_shadow', chip._shadow & 0x01, 1)

chip.write_port(mask=0xFF)
check_true('read_all_input', 0 <= chip.read_port() <= 0xFF)

# Full: polling-thread interrupt (int_pin=None on Linux)
full = Pcf8574Full(transport)
changed = full.clear_interrupt()
check_true('clear_interrupt_range', 0 <= changed <= 0xFF)

# Verify polling thread starts and stops cleanly
results = []

def _cb(mask):
    results.append(mask)

full.configure_interrupt(None, _cb)
check_true('poll_thread_running', full._poll_thread is not None and full._poll_thread.is_alive())
full._poll_stop = True
full._poll_thread.join(timeout=0.1)
check_true('poll_thread_stopped', not full._poll_thread.is_alive())

print('===DONE: {} passed, {} failed==='.format(passed, failed))
