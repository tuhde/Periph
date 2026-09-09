import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.io_expander.mcp23017 import Mcp23017Full

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


# MCP23017's _read_reg() issues a plain 1-byte register-pointer write
# followed by a plain read() (not write_read()), so I2CConnectionMock's
# register map is not consulted on reads here — every read must be
# preloaded via queue_read() in the exact order the driver will issue it.
connection = I2CConnectionMock()
chip = Mcp23017Full(connection)
check_true('init', True)

# Init sequence: OLATA/OLATB=0x00, IODIRA/IODIRB=0x7F (GPA7/GPB7 forced
# output-only), IPOLA/IPOLB=0x00, GPPUA/GPPUB=0x00.
check_true('init_olata', connection.registers[Mcp23017Full._REG_OLATA] == 0x00)
check_true('init_olatb', connection.registers[Mcp23017Full._REG_OLATB] == 0x00)
check_true('init_iodira_output_only', connection.registers[Mcp23017Full._REG_IODIRA] == 0x7F)
check_true('init_iodirb_output_only', connection.registers[Mcp23017Full._REG_IODIRB] == 0x7F)
check_true('init_ipola', connection.registers[Mcp23017Full._REG_IPOLA] == 0x00)
check_true('init_ipolb', connection.registers[Mcp23017Full._REG_IPOLB] == 0x00)
check_true('init_gppua', connection.registers[Mcp23017Full._REG_GPPUA] == 0x00)
check_true('init_gppub', connection.registers[Mcp23017Full._REG_GPPUB] == 0x00)

# read_port(0)/(1) -> GPIOA/GPIOB.
connection.queue_read([0xA5])
check_true('read_port_a', chip.read_port(0) == 0xA5)
connection.queue_read([0x5A])
check_true('read_port_b', chip.read_port(1) == 0x5A)

# write_port updates OLAT register and shadow.
chip.write_port(0, 0x3C)
check_true('write_port_a_register', connection.registers[Mcp23017Full._REG_OLATA] == 0x3C)
check_true('write_port_a_shadow', chip._shadow[0] == 0x3C)

# pin() read on PORTA and PORTB.
pin0 = chip.pin(0)
connection.queue_read([0x01])  # GPA0 high
check_true('pin_read_porta', pin0.value() == 1)

pin9 = chip.pin(9)  # GPB1
connection.queue_read([0x02])  # GPB1 high
check_true('pin_read_portb', pin9.value() == 1)

# Pin direction: setting a pin to OUT clears its IODIRA bit; IN sets it.
pin1 = chip.pin(1)
pin1.init(Mcp23017Full.OUT)
check_true('pin_output_clears_iodir', connection.registers[Mcp23017Full._REG_IODIRA] == (0x7F & ~0x02))
pin1.init(Mcp23017Full.IN)
check_true('pin_input_sets_iodir', connection.registers[Mcp23017Full._REG_IODIRA] == 0x7F)

# Pin set high/low preserves other output bits (read-modify-write via shadow).
chip.write_port(0, 0x00)
pin0.on()
check_true('pin0_on', connection.registers[Mcp23017Full._REG_OLATA] == 0x01)
pin2 = chip.pin(2)
pin2.on()
check_true('pin2_on_preserves_pin0', connection.registers[Mcp23017Full._REG_OLATA] == 0x05)
pin0.off()
check_true('pin0_off_preserves_pin2', connection.registers[Mcp23017Full._REG_OLATA] == 0x04)

# Toggle.
pin0.toggle()
check_true('pin0_toggle_on', connection.registers[Mcp23017Full._REG_OLATA] == 0x05)
pin0.toggle()
check_true('pin0_toggle_off', connection.registers[Mcp23017Full._REG_OLATA] == 0x04)

# Full: configure_pullup / configure_polarity / set_default_value.
chip.configure_pullup(0, 0xFF)
check_true('configure_pullup', connection.registers[Mcp23017Full._REG_GPPUA] == 0xFF)
chip.configure_polarity(1, 0x0F)
check_true('configure_polarity', connection.registers[Mcp23017Full._REG_IPOLB] == 0x0F)
chip.set_default_value(0, 0x11)
check_true('set_default_value', connection.registers[Mcp23017Full._REG_DEFVALA] == 0x11)

# poll_interrupt(port): reads INTF then INTCAP (discarded); returns INTF value.
connection.queue_read([0x08])   # INTFA
connection.queue_read([0xFF])   # INTCAPA (discarded)
check_true('poll_interrupt', chip.poll_interrupt(0) == 0x08)

# read_capture(port): reads INTCAP directly.
connection.queue_read([0x22])   # INTCAPB
check_true('read_capture', chip.read_capture(1) == 0x22)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
