"""Linux hardware test for the TMC2208 — runs against a wired driver board.

Checks identity (IOIN), IFCNT write acknowledgement, microstep and current
registers, and spins the motor briefly with VACTUAL.
"""

import time
import os
from periph.connection.uart_linux import UARTConnection
from periph.chips.motor.tmc2208 import TMC2208Full

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


UART_PORT = os.environ.get('UART_PORT', '/dev/ttyS0')
UART_BAUD = int(os.environ.get('UART_BAUD', '115200'))
connection = UARTConnection(UART_PORT, baudrate=UART_BAUD, timeout_s=0.1)
motor = TMC2208Full(connection)

check_true('construct_full', isinstance(motor, TMC2208Full))
ioin = motor.read_ioin()
check_true('ioin_version', ioin['version'] not in (0x00, 0xFF))
check_true('ioin_sel_a', ioin['sel_a'])
ifcnt = motor.read_ifcnt()
motor.set_current(0.5)
check_true('ifcnt_advances', motor.read_ifcnt() == (ifcnt + 2) % 256)
motor.set_microsteps(16)
check_true('mres_readback', (motor.read_register(0x6C) >> 24) & 0xF == 4)
check_true('gconf_pdn_disable', motor.read_register(0x00) & 0x40 == 0x40)
motor.enable()
motor.run(100)
time.sleep(1)
check_true('moving', not motor.is_standstill())
check_true('tstep_nonzero', motor.read_tstep() > 0)
check_true('no_fault', motor.read_fault() == 0)
motor.stop()
time.sleep(0.5)
motor.disable()
check_true('status_dict', 'cs_actual' in motor.read_status())

print('===DONE: %d passed, %d failed===' % (passed, failed))
