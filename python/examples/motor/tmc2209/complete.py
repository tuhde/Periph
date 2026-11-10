"""Complete example for the TMC2209 — exercise every method in the public API.

Constructs the Full driver, tunes current, microstepping, chopper and
StealthChop, StallGuard4 and CoolStep, moves the motor with the internal generator and with GPIO STEP
pulses, and reads back every status register.
"""

import time
from machine import UART, Pin
from periph.connection.uart_micropython import UARTConnection
from periph.connection.input_pin import MicroPythonPin
from periph.connection.output_pin import MicroPythonOutputPin
from periph.chips.motor.tmc2209 import TMC2209Full

uart = UART(1, baudrate=115200, tx=Pin(17), rx=Pin(16), timeout=100)
connection = UARTConnection(uart, 115200)
motor = TMC2209Full(connection, r_sense_ohm=0.11, address=0, en_pin=MicroPythonOutputPin(4),
                    step_pin=MicroPythonOutputPin(5), dir_pin=MicroPythonOutputPin(18),
                    diag_pin=MicroPythonPin(Pin(19, Pin.IN)))  # Create TMC2209 Full driver, (connection, r_sense_ohm=0.11 Ω, address=0, en_pin, echo=True, step_pin, dir_pin, diag_pin, multi_node=False, fclk_hz=12000000 Hz)
                                                         # reads IOIN to verify the chip, writes the default configuration and checks IFCNT

motor.enable()                                           # Power stage on, () → None
                                                         # drives ENN low
motor.set_current(0.8)                                   # Set run current, (rms_a A RMS) → None
                                                         # converts amperes to the 0–31 scale; hold current becomes a third
motor.set_hold_current(0.2)                              # Set hold current, (rms_a A RMS) → None
                                                         # overrides the automatic one-third hold current
motor.set_current_delay(6, 20)                           # Set power-down timing, (ihold_delay 0–15, tpowerdown 0–255) → None
                                                         # IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
motor.set_microsteps(32)                                 # Set microstep resolution, (n 1–256) → None
                                                         # writes CHOPCONF.MRES
motor.set_interpolation(True)                            # Interpolate to 256 microsteps, (enabled) → None
                                                         # sets CHOPCONF.intpol
motor.set_chopper_mode(False)                            # Choose chopper, (spreadcycle) → None
                                                         # False selects StealthChop
motor.set_stealthchop_threshold(300)                     # Set StealthChop velocity limit, (tpwmthrs 20 bit) → None
                                                         # faster than this the chip switches to SpreadCycle
motor.configure_chopper(3, 2, 5, 0)                      # Configure chopper timing, (toff, tbl, hstrt, hend) → None
                                                         # direct CHOPCONF fields
motor.configure_stealthchop(True, True, 36, 14, 1, 4, 12)  # Configure StealthChop, (autoscale, autograd, pwm_ofs, pwm_grad, pwm_freq, pwm_reg, pwm_lim) → None
                                                         # direct PWMCONF fields
motor.set_freewheel(0)                                   # Set standstill mode, (mode 0–3) → None
                                                         # PWMCONF.freewheel, only effective when IHOLD is 0
motor.set_vsense(False)                                  # Select sense range, (high_sensitivity) → None
                                                         # False keeps the 0.325 V full scale
motor.set_analog_scaling(True)                           # Current reference source, (enabled) → None
                                                         # True takes the reference from VREF
motor.set_direction_inverted(False)                      # Invert direction, (inverted) → None
                                                         # GCONF.shaft
motor.set_index_output(0)                                # Select INDEX output, (mode 0–2) → None
                                                         # 0 first microstep, 1 pre-warning, 2 step pulses
motor.set_driver_protection(True, True)                  # Short protections, (short_ls, short_gnd) → None
                                                         # True keeps the protection enabled
motor.set_double_edge(False)                             # Step on both edges, (enabled) → None
                                                         # CHOPCONF.dedge
motor.set_send_delay(0)                                  # Set reply delay, (n 0–15) → None
                                                         # NODECONF.SENDDELAY

motor.set_stall_threshold(60)                            # Set StallGuard4 threshold, (sgthrs 0–255) → None
                                                         # stall when SG_RESULT <= 2 * SGTHRS
motor.set_coolstep_threshold(1000)                       # Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None
                                                         # active while TCOOLTHRS >= TSTEP > TPWMTHRS
motor.configure_coolstep(5, 2, 1, 1, 0)                  # Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
                                                         # semin = 0 would disable CoolStep

motor.run(300)                                           # Spin with the internal generator, (steps_per_s full steps/s) → None
                                                         # converts to VACTUAL using the 12 MHz clock
time.sleep(2)
print('position', motor.microstep_position())            # Read microstep counter, () → int 0–1023
                                                         # MSCNT
print('currents', motor.read_coil_currents())            # Read coil currents, () → (int, int) table values
                                                         # two signed 9-bit MSCURACT fields
print('sg_result', motor.read_stallguard())            # Read StallGuard4 result, () → int 0–510
                                                         # SG_RESULT, higher = less load
print('stalled', motor.is_stalled())                    # Read DIAG pin, () → bool
                                                         # true on a stall or a driver error
print('tstep', motor.read_tstep())                       # Read step interval, () → int clocks
                                                         # time between 1/256 microsteps
print('scale', motor.read_current_scale())               # Read actual current scale, () → int 0–31
                                                         # DRV_STATUS.CS_ACTUAL
print('temp', motor.read_temperature_flags())            # Read temperature flags, () → int 0–15
                                                         # bit 0 t120 … bit 3 t157
print('status', motor.read_status())                     # Read driver status, () → dict
                                                         # decoded DRV_STATUS
print('standstill', motor.is_standstill())               # Read standstill flag, () → bool
                                                         # DRV_STATUS.stst
print('fault', motor.read_fault())                       # Read fault bitmask, () → int
                                                         # FAULT_* constants, 0 = none
motor.stop()                                             # Stop the motor, () → None
                                                         # VACTUAL = 0, STEP input takes over again

motor.step(400, 0.001)                                   # Pulse the STEP pin, (count, delay_s s) → None
                                                         # negative counts reverse via DIR
print('gstat', motor.read_global_status(clear=True))     # Read and clear GSTAT, (clear=False) → dict
                                                         # reset, drv_err, uv_cp
print('ifcnt', motor.read_ifcnt())                       # Read write counter, () → int
                                                         # IFCNT
print('ioin', motor.read_ioin())                          # Read IOIN, () → dict
                                                         # raw word, decoded pins and silicon version
print('otp', hex(motor.read_otp()))                       # Read OTP image, () → int
                                                         # OTP_READ
print('pwm_auto', motor.read_pwm_auto())                 # Read StealthChop tuning, () → dict
                                                         # PWM_SCALE and PWM_AUTO results
print('gconf', hex(motor.read_register(0x00)))           # Read a raw register, (reg) → int
                                                         # any readable register
motor.write_register(0x13, 300)                          # Write a raw register, (reg, value) → None
                                                         # here TPWMTHRS again
motor.disable()                                          # Power stage off, () → None
                                                         # drives ENN high; the motor freewheels
