import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.connection.spi_mock import SPIConnectionMock
from periph.chips.io_expander.mcp23008 import (
    Mcp23008Minimal, Mcp23008Full, Mcp23S08Minimal, Mcp23S08Full)

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


# ---------------------------------------------------------------- I2C
connection = I2CConnectionMock()
chip = Mcp23008Full(connection)

# Init: OLAT cleared, IODIR left at reset (all inputs), shadows set.
check_true('init_olat', connection.registers[0x0A] == 0x00)
check_true('init_shadow', chip._shadow == 0x00 and chip._direction == 0xFF)
check_true('init_no_iodir_write', all(w[0] != 0x00 for w in connection.writes))

# read_port -> GPIO (0x09); port argument ignored.
connection.registers[0x09] = 0xA5
check_true('read_port', chip.read_port() == 0xA5)
check_true('read_port_ignores_port', chip.read_port(1) == 0xA5)

# write_port -> OLAT (0x0A) and shadow.
chip.write_port(0, 0x3C)
check_true('write_port_register', connection.registers[0x0A] == 0x3C)
check_true('write_port_shadow', chip._shadow == 0x3C)

# Pin direction: output clears the IODIR bit, input sets it; GP7 is bidirectional.
pin7 = chip.pin(7)
pin7.init(Mcp23008Full.OUT)
check_true('gp7_output', connection.registers[0x00] == 0x7F)
pin7.init(Mcp23008Full.IN)
check_true('gp7_input', connection.registers[0x00] == 0xFF)

# OLAT is written before IODIR when a pin becomes an output.
connection.writes.clear()
chip.write_port(0, 0x00)
connection.writes.clear()
chip.pin(1).init(Mcp23008Full.OUT)
check_true('olat_before_iodir',
           [w[0] for w in connection.writes] == [0x0A, 0x00])

# Pin read, set, clear, toggle with shadow preservation.
chip.write_port(0, 0x00)
connection.registers[0x09] = 0x04
check_true('pin_read', chip.pin(2).value() == 1)
chip.pin(0).on()
check_true('pin0_on', connection.registers[0x0A] == 0x01)
chip.pin(2).on()
check_true('pin2_on_preserves_pin0', connection.registers[0x0A] == 0x05)
chip.pin(0).off()
check_true('pin0_off_preserves_pin2', connection.registers[0x0A] == 0x04)
chip.pin(0).toggle()
check_true('pin0_toggle_on', connection.registers[0x0A] == 0x05)
chip.pin(0).toggle()
check_true('pin0_toggle_off', connection.registers[0x0A] == 0x04)

# Full configuration registers.
chip.configure_pullup(0xF0)
check_true('configure_pullup', connection.registers[0x06] == 0xF0)
chip.configure_polarity(0x0F)
check_true('configure_polarity', connection.registers[0x01] == 0x0F)
chip.set_default_value(0x11)
check_true('set_default_value', connection.registers[0x03] == 0x11)
chip.enable_pin_interrupt(0xF0)
check_true('enable_pin_interrupt', connection.registers[0x02] == 0xF0)

# IOCON: ODR / INTPOL / SEQOP.
chip.configure_int_pin(open_drain=False, active_high=True)
check_true('iocon_intpol', connection.registers[0x05] == 0x02)
chip.configure_int_pin(open_drain=True, active_high=True)
check_true('iocon_odr', connection.registers[0x05] == 0x04)
chip.configure_int_pin()
check_true('iocon_default', connection.registers[0x05] == 0x00)
chip.set_sequential(False)
check_true('iocon_seqop_off', connection.registers[0x05] == 0x20)
chip.set_sequential(True)
check_true('iocon_seqop_on', connection.registers[0x05] == 0x00)

# poll_interrupt: INTF then INTCAP; read_capture: INTCAP.
connection.registers[0x07] = 0x10
connection.registers[0x08] = 0xFF
check_true('poll_interrupt', chip.poll_interrupt() == 0x10)
connection.registers[0x08] = 0x22
check_true('read_capture', chip.read_capture() == 0x22)

# Raw access keeps shadows in sync.
chip.write_register(0x00, 0x0F)
check_true('write_register_iodir_shadow', chip._direction == 0x0F)
chip.write_register(0x0A, 0x81)
check_true('write_register_olat_shadow', chip._shadow == 0x81)
check_true('read_register', chip.read_register(0x0A) == 0x81)

# on_interrupt with no armed pins raises.
chip2 = Mcp23008Full(I2CConnectionMock())
try:
    chip2.on_interrupt(lambda s: None)
    check_true('on_interrupt_unarmed_raises', False)
except ValueError:
    check_true('on_interrupt_unarmed_raises', True)

# watch() on an output pin raises.
chip2.pin(0).init(Mcp23008Full.OUT)
try:
    chip2.pin(0).watch(lambda p: None)
    check_true('watch_output_raises', False)
except ValueError:
    check_true('watch_output_raises', True)

# reset: every register back to its reset value.
chip.reset()
check_true('reset_iodir', connection.registers[0x00] == 0xFF)
check_true('reset_olat', connection.registers[0x0A] == 0x00)
check_true('reset_gppu', connection.registers[0x06] == 0x00)
check_true('reset_gpinten', connection.registers[0x02] == 0x00)
check_true('reset_shadows', chip._shadow == 0x00 and chip._direction == 0xFF)

# ---------------------------------------------------------------- SPI
spi = SPIConnectionMock()
schip = Mcp23S08Full(spi, 2)

# Init: IOCON <- HAEN with the address-0 opcode first, then OLAT <- 0 with the real opcode.
check_true('spi_init_haen_addr0', spi.writes[0] == bytes([0x40, 0x05, 0x08]))
check_true('spi_init_olat', spi.writes[1] == bytes([0x44, 0x0A, 0x00]))

# Write framing: opcode (0x40 | A<<1), reg, data.
schip.write_port(0, 0x5A)
check_true('spi_write_port', spi.writes[-1] == bytes([0x44, 0x0A, 0x5A]))

# Read framing: opcode with R/W=1 then register; result from the register map.
spi.set_register(0x09, 0xC3)
check_true('spi_read_port', schip.read_port() == 0xC3)
check_true('spi_read_header', spi.writes[-1] == bytes([0x45, 0x09]))

schip.configure_pullup(0x0F)
check_true('spi_configure_pullup', spi.writes[-1] == bytes([0x44, 0x06, 0x0F]))

# IOCON keeps HAEN when other bits change.
schip.configure_int_pin(open_drain=True)
check_true('spi_iocon_keeps_haen', spi.writes[-1] == bytes([0x44, 0x05, 0x0C]))
schip.reset()
check_true('spi_reset_keeps_haen', bytes([0x44, 0x05, 0x08]) in spi.writes)

# Minimal classes are constructible.
check_true('minimal_i2c', Mcp23008Minimal(I2CConnectionMock()).read_port() == 0)
check_true('minimal_spi', Mcp23S08Minimal(SPIConnectionMock(), 0).read_port() == 0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
