import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.mcp4xxx import Mcp4xxxFull, default_address

I2C_BUS = int(os.environ.get('I2C_BUS', '1'))
PART = os.environ.get('MCP4XXX_PART', 'MCP4532')
I2C_ADDR = int(os.environ.get('I2C_ADDR', hex(default_address(PART))), 16)

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


connection = I2CConnection(I2C_BUS, I2C_ADDR)

chip = Mcp4xxxFull(connection, PART)
max_code = chip.steps() - 1

# --- Wiper write / readback ---
chip.set_wiper(100 % (max_code + 1))
check_eq('set/get wiper', chip.get_wiper(), 100 % (max_code + 1))
check_eq('clamps high', (chip.set_wiper(9999), chip.get_wiper())[1], max_code)
check_eq('clamps low', (chip.set_wiper(-5), chip.get_wiper())[1], 0)
if chip.channels() == 2:
    chip.set_both(10, 20)
    check_eq('set_both ch0', chip.get_wiper(0), 10)
    check_eq('set_both ch1', chip.get_wiper(1), 20)

# --- Resistance and ratio ---
chip.set_resistance(5075.0)
check_true('set_resistance ~mid', abs(chip.get_wiper() - max_code // 2) <= 1)
check_true('get_resistance ~5075', abs(chip.get_resistance() - 5075.0) < 100.0)
chip.set_ratio(0.5)
check_true('get_ratio ~0.5', abs(chip.get_ratio() - 0.5) < 0.01)
if chip.is_potentiometer():
    chip.set_wiper(0)
    check_true('R_WA at zero scale ~RAB', abs(chip.get_resistance_wa() - 10075.0) < 100.0)

# --- Increment / decrement (continuous) ---
chip.set_wiper(max_code - 3)
check_eq('increment saturates', chip.increment(10), max_code)
chip.set_wiper(3)
check_eq('decrement saturates', chip.decrement(10), 0)
check_eq('increment 1', chip.increment(), 1)

# --- Shutdown / terminals ---
chip.set_wiper(max_code // 4)
chip.shutdown()
check_true('is_shutdown', chip.is_shutdown())
chip.resume()
check_true('not shutdown', not chip.is_shutdown())
check_eq('wiper kept across shutdown', chip.get_wiper(), max_code // 4)
chip.set_terminals(w=False)
check_eq('terminals', chip.get_terminals(), (True, False, True))
chip.set_terminals()
check_eq('tcon restored', chip.get_tcon() & 0xFF, 0xFF)

# --- Non-volatile parts ---
if chip.is_nonvolatile():
    status = chip.read_status()
    check_true('status no busy', not status['eeprom_busy'])
    chip.set_wiper(max_code // 2)
    check_eq('store_wiper', chip.store_wiper(), max_code // 2)
    check_eq('get_nv_wiper', chip.get_nv_wiper(), max_code // 2)
    chip.write_data(0, 0x155)
    check_eq('data roundtrip', chip.read_data(0), 0x155)

# --- Reset ---
chip.set_wiper(1)
chip.reset()
check_eq('reset wiper', chip.get_wiper(), max_code // 2)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
