import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.mpl115a2 import MPL115A2Minimal, MPL115A2Full

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


def make():
    # Datasheet worked example: a0=0x3ECE, b1=0xB3F9, b2=0xC517, c12=0x33C8;
    # ADC bytes 0x66 0x80 0x7E 0xC0 -> Padc=410, Tadc=507 -> 96.59 kPa.
    c = I2CConnectionMock()
    c.set_register(MPL115A2Full._REG_COEF, 0x3E, 0xCE, 0xB3, 0xF9, 0xC5, 0x17, 0x33, 0xC8)
    c.set_register(MPL115A2Full._REG_PADC, 0x66, 0x80, 0x7E, 0xC0)
    return c


connection = make()
sensor = MPL115A2Full(connection)
check_true('init', True)

a0, b1, b2, c12 = sensor.coefficients()
check_true('coefficients', abs(a0 - 2009.75) < 1e-9 and abs(b1 - (-2.375854)) < 1e-5
           and abs(b2 - (-0.92047)) < 1e-5 and abs(c12 - 0.000790) < 1e-6)

p = sensor.read_pressure()
check_true('pressure', abs(p - 96586.0) < 30.0)
convert_writes = [w for w in connection.writes if len(w) == 2 and w[0] == 0x12]
check_true('convert_written', len(convert_writes) == 1 and convert_writes[0][1] == 0x00)

check_true('temperature', abs(sensor.read_temperature() - (25.0 + (507 - 498) / -5.35)) < 1e-9)
check_true('read_raw', sensor.read_raw() == (410, 507))
r = sensor.read()
check_true('read', abs(r['pressure_pa'] - p) < 1e-9 and abs(r['temperature_c'] - sensor.read_temperature()) < 1e-9)
check_true('compensate', abs(sensor.compensate(410, 507) - p) < 1e-9)
check_true('compensate_limits', sensor.compensate(0, 0) > 40000 and sensor.compensate(1023, 0) < 130000)
check_true('pressure_hpa', abs(sensor.pressure_hpa() - p / 100.0) < 1e-9)
check_true('altitude', abs(sensor.altitude() - 44330.0 * (1.0 - (p / 101325.0) ** (1.0 / 5.255))) < 1e-6)
check_true('altitude_sea_level', abs(sensor.altitude(p)) < 1e-9)

# reload_coefficients re-reads the 8-byte block
connection.set_register(MPL115A2Full._REG_COEF, 0x00, 0x08, 0, 0, 0, 0, 0, 0)
sensor.reload_coefficients()
check_true('reload_coefficients', sensor.coefficients()[0] == 1.0)

# Minimal class on its own
minimal = MPL115A2Minimal(make())
check_true('minimal_pressure', abs(minimal.read_pressure() - 96586.0) < 30.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
