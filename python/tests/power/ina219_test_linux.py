import os
from periph.transport.i2c_linux import I2CTransport
from periph.chips.power.ina219 import INA219Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x40'), 16)


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:04X}, expected 0x{:04X}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


transport = I2CTransport(I2C_BUS, I2C_ADDR)
ina = INA219Full(transport)

check_true('voltage non-negative', ina.voltage() >= 0.0)
check_true('shunt_voltage finite', ina.shunt_voltage() > -1.0)
check_true('current finite', ina.current() > -10.0)
check_true('power non-negative', ina.power() >= 0.0)

check_true('conversion_ready', ina.conversion_ready())
check_true('no overflow', not ina.overflow())

ina.configure(INA219Full.BRNG_32V, INA219Full.PGA_8, INA219Full.ADC_12BIT, INA219Full.ADC_12BIT, INA219Full.MODE_SHUNT_BUS_CONT)

check_true('configure: voltage non-negative', ina.voltage() >= 0.0)

ina.shutdown()
ina.wake()
check_true('wake: voltage non-negative', ina.voltage() >= 0.0)

ina.reset()
check_true('reset: voltage non-negative', ina.voltage() >= 0.0)

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))