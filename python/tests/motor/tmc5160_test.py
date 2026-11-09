"""MicroPython hardware test for the TMC5160 — runs against a wired driver board.

Checks identity (IOIN), verified writes, ramp-generator positioning and
velocity mode, and the status read-backs.
"""

import time
import _testconfig as cfg
from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc5160 import TMC5160Full

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


spi = SPI(cfg.SPI_ID, baudrate=1_000_000, polarity=1, phase=1)
connection = SPIConnection(spi, Pin(cfg.SPI_CS, Pin.OUT))
en_pin = MicroPythonOutputPin(cfg.EN_PIN)
motor = TMC5160Full(connection, r_sense_ohm=0.075, en_pin=en_pin)

check_true('construct_full', isinstance(motor, TMC5160Full))
ioin = motor.read_ioin()
check_true('ioin_version', ioin['version'] == 0x30)
check_true('ioin_not_stepdir', not ioin['sd_mode'])
motor.write_register(0x13, 600, verify=True)
check_true('verified_write', True)
motor.set_current(1.0)
motor.set_position(0)
check_true('position_zero', motor.position() == 0)
motor.enable()
motor.move(50)
check_true('position_reached', motor.is_position_reached())
check_true('position_50', abs(motor.position() - 50) < 1)
motor.run(100)
time.sleep(0.5)
check_true('velocity_nonzero', motor.velocity() > 0)
motor.stop()
motor.wait_until_reached()
check_true('standstill_after_stop', motor.velocity() == 0)
check_true('no_fault', motor.read_fault() == 0)
check_true('tstep_readable', motor.read_tstep() >= 0)
motor.set_stall_threshold(0)
check_true('sg_result_range', 0 <= motor.read_stallguard() <= 1023)
motor.disable()
check_true('status_dict', 'cs_actual' in motor.read_status())

print('===DONE: %d passed, %d failed===' % (passed, failed))
