"""Linux hardware test for the TMC2130 — runs against a wired driver board.

Checks identity (IOIN), mirror-verified writes, microstep and current
registers, and moves the motor briefly with STEP/DIR pulses.
"""

import time
import os
from periph.connection.spi_linux import SPIConnection
from periph.connection.output_pin import LinuxOutputPin
from periph.chips.motor.tmc2130 import TMC2130Full

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


bus = int(os.environ.get('SPI_BUS', '0'))
device = int(os.environ.get('SPI_DEVICE', '0'))
chip = os.environ.get('GPIO_CHIP', '/dev/gpiochip0')
connection = SPIConnection(bus_num=bus, device_num=device, mode=3, max_speed_hz=1_000_000)
step_pin = LinuxOutputPin(chip, int(os.environ.get('STEP_LINE', '27')))
dir_pin = LinuxOutputPin(chip, int(os.environ.get('DIR_LINE', '22')))
en_pin = LinuxOutputPin(chip, int(os.environ.get('EN_LINE', '17')))
motor = TMC2130Full(connection, step_pin, dir_pin, en_pin=en_pin)

check_true('construct_full', isinstance(motor, TMC2130Full))
ioin = motor.read_ioin()
check_true('ioin_version', ioin['version'] == 0x11)
motor.write_register(0x13, 600, verify=True)
check_true('verified_write', True)
motor.set_current(0.5)
motor.set_microsteps(16)
check_true('mres_readback', (motor.read_register(0x6C) >> 24) & 0xF == 4)
check_true('gconf_readback', motor.read_register(0x00) & 0x4 == 0x4)
motor.enable()
motor.move(50, 100)
check_true('no_fault', motor.read_fault() == 0)
check_true('tstep_readable', motor.read_tstep() >= 0)
motor.set_stall_threshold(0)
check_true('sg_result_range', 0 <= motor.read_stallguard() <= 1023)
motor.disable()
check_true('status_dict', 'cs_actual' in motor.read_status())
check_true('gstat_dict', 'reset' in motor.read_global_status())

print('===DONE: %d passed, %d failed===' % (passed, failed))
