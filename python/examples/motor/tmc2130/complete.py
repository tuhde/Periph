"""Complete example for the TMC2130 — exercise every method in the public API.

Constructs the Full driver, tunes current, microstepping, chopper and
StealthChop, configures StallGuard2 / CoolStep / DIAG routing, moves the
motor with STEP/DIR pulses and with direct coil currents, and reads back
every status register.
"""

import time
from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.connection.input_pin import MicroPythonPin
from periph.chips.motor.tmc2130 import TMC2130Full

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)
connection = SPIConnection(spi, Pin(5, Pin.OUT))
motor = TMC2130Full(connection, MicroPythonOutputPin(6), MicroPythonOutputPin(9),
                    r_sense_ohm=0.11, en_pin=MicroPythonOutputPin(4),
                    diag0_pin=MicroPythonPin(Pin(19, Pin.IN)),
                    diag1_pin=MicroPythonPin(Pin(20, Pin.IN)))  # Create TMC2130 Full driver, (connection, step_pin, dir_pin, r_sense_ohm=0.11 Ω, en_pin, diag0_pin, diag1_pin, fclk_hz=12000000 Hz)
                                                         # reads IOIN to verify the chip, writes the default configuration with mirror checks

motor.enable()                                           # Power stage on, () → None
                                                         # drives ENN low, waits 100 ms
motor.set_current(0.8)                                   # Set run current, (rms_a A RMS) → None
                                                         # converts amperes to the 0–31 scale, picks vsense automatically; hold current becomes a third
motor.set_hold_current(0.2)                              # Set hold current, (rms_a A RMS) → None
                                                         # overrides the automatic one-third hold current
motor.set_current_delay(6, 10)                           # Set power-down timing, (ihold_delay 0–15, tpowerdown 0–255) → None
                                                         # IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
motor.set_microsteps(32)                                 # Set microstep resolution, (n 1–256) → None
                                                         # writes CHOPCONF.MRES
motor.set_interpolation(True)                            # Interpolate to 256 microsteps, (enabled) → None
                                                         # sets CHOPCONF.intpol
motor.set_chopper_mode(False)                            # Choose chopper, (spreadcycle) → None
                                                         # False enables StealthChop below TPWMTHRS
motor.set_stealthchop_threshold(500)                     # Set StealthChop velocity limit, (tpwmthrs 20 bit) → None
                                                         # faster than this the chip switches to SpreadCycle
motor.set_high_velocity_threshold(0)                     # Set THIGH, (thigh 20 bit) → None
                                                         # 0 keeps the high-velocity features off
motor.configure_chopper(3, 2, 4, 1)                      # Configure SpreadCycle timing, (toff, tbl, hstrt, hend) → None
                                                         # direct CHOPCONF fields with chm = 0
motor.configure_constant_off_time(3, 2, 4, 0, False, False)  # Configure constant off time, (toff, tbl, tfd, offset, disfdcc, rndtf) → None
                                                         # chm = 1 with fast decay
motor.configure_chopper(3, 2, 4, 1)                      # Back to SpreadCycle, (toff, tbl, hstrt, hend) → None
                                                         # chm = 0 again
motor.configure_high_velocity(False, False)              # Behaviour above THIGH, (vhighchm, vhighfs) → None
                                                         # both off
motor.set_chopper_sync(0)                                # Set chopSync2, (sync 0–15) → None
                                                         # 0 = off
motor.configure_stealthchop(True, False, 200, 1, 0)      # Configure StealthChop, (autoscale, symmetric, pwm_ampl, pwm_grad, pwm_freq) → None
                                                         # direct PWMCONF fields
motor.set_freewheel(0)                                   # Set standstill mode, (mode 0–3) → None
                                                         # PWMCONF.freewheel, only effective when IHOLD is 0
motor.set_vsense(False)                                  # Select sense range, (high_sensitivity) → None
                                                         # False keeps the 0.325 V full scale; disables automatic selection
motor.set_internal_rsense(False)                         # Internal sense resistors, (enabled) → None
                                                         # keep False on boards with external resistors
motor.set_analog_scaling(False)                          # Current reference source, (enabled) → None
                                                         # False uses the internal reference
motor.set_direction_inverted(False)                      # Invert direction, (inverted) → None
                                                         # GCONF.shaft
motor.configure_diag(diag0_stall=True, diag1_index=True)  # Route events to DIAG pins, (diag0_error, diag0_otpw, diag0_stall, diag1_stall, diag1_index, diag1_onstate, diag0_pushpull, diag1_pushpull) → None
                                                         # GCONF bits 5–13
motor.set_double_edge(False)                             # Step on both edges, (enabled) → None
                                                         # CHOPCONF.dedge
motor.set_short_protection(True)                         # Short-to-GND protection, (short_gnd) → None
                                                         # True keeps the protection enabled
motor.set_small_hysteresis(False)                        # Comparison hysteresis, (enabled) → None
                                                         # False = 1/16
motor.set_emergency_stop(False)                          # DCIN emergency stop, (enabled) → None
                                                         # GCONF.stop_enable
motor.set_stall_threshold(0)                             # Set StallGuard2 threshold, (sgt -64..63) → None
                                                         # lower = more sensitive
motor.set_stallguard_filter(False)                       # StallGuard2 filter, (enabled) → None
                                                         # COOLCONF.sfilt
motor.set_coolstep_threshold(1000)                       # Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None
                                                         # active while TCOOLTHRS >= TSTEP >= THIGH
motor.configure_coolstep(5, 2, 1, 1, 0)                  # Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
                                                         # semin = 0 would disable CoolStep

motor.move(200, 150)                                     # Move, (full_steps, full_steps_per_s full steps/s) → None
                                                         # blocking, software-timed STEP pulses
motor.step(400, 0.001)                                   # Pulse the STEP pin, (count, delay_s s) → None
                                                         # negative counts reverse via DIR
print('position', motor.microstep_position())            # Read microstep counter, () → int 0–1023
                                                         # MSCNT
print('currents', motor.read_coil_currents())            # Read coil currents, () → (int, int) table values
                                                         # signed (CUR_A, CUR_B) from MSCURACT
print('tstep', motor.read_tstep())                       # Read step interval, () → int clocks
                                                         # time between 1/256 microsteps
print('scale', motor.read_current_scale())               # Read actual current scale, () → int 0–31
                                                         # DRV_STATUS.CS_ACTUAL
print('sg_result', motor.read_stallguard())              # Read StallGuard2 result, () → int 0–1023
                                                         # SG_RESULT, 0 = highest load
print('stalled', motor.is_stalled())                     # Read stall flag, () → bool
                                                         # DRV_STATUS.StallGuard
print('diag0', motor.read_diag(0))                       # Read DIAG0 pin, (n 0 or 1) → bool
                                                         # needs the diag pin to be wired
print('pwm_scale', motor.read_pwm_scale())               # Read PWM scaler, () → int 0–255
                                                         # PWM_SCALE
print('lost_steps', motor.read_lost_steps())             # Read skipped DcStep steps, () → int
                                                         # LOST_STEPS
print('status', motor.read_status())                     # Read driver status, () → dict
                                                         # decoded DRV_STATUS
print('standstill', motor.is_standstill())               # Read standstill flag, () → bool
                                                         # DRV_STATUS.stst
print('fault', motor.read_fault())                       # Read fault bitmask, () → int
                                                         # FAULT_* constants, 0 = none
print('gstat', motor.read_global_status())               # Read GSTAT, () → dict
                                                         # reset, drv_err, uv_cp; the chip clears them on read
print('ioin', motor.read_ioin())                         # Read IOIN, () → dict
                                                         # raw word, pin states and silicon version
print('gconf', hex(motor.read_register(0x00)))           # Read a raw register, (reg) → int
                                                         # any readable register
motor.write_register(0x13, 500, verify=True)             # Write a raw register, (reg, value, verify=False) → None
                                                         # here TPWMTHRS again, with the mirror check

motor.set_dcstep(0, 0, 0)                                # Configure DcStep, (vdcmin, dc_time, dc_sg) → None
                                                         # all zero keeps DcStep off
motor.set_direct_mode(True)                              # Direct coil-current mode, (enabled) → None
                                                         # GCONF.direct_mode
motor.set_coil_currents(100, 0)                          # Write XDIRECT, (coil_a, coil_b -255..255) → None
                                                         # scaled by IHOLD
time.sleep(0.5)
motor.set_direct_mode(False)                             # Back to STEP/DIR, (enabled) → None
                                                         # GCONF.direct_mode = 0
CUSTOM_TABLE = None                                      # replace with (mslut[8], mslutsel, mslutstart) to program a table
if CUSTOM_TABLE and motor.microstep_position() == 0:
    motor.write_microstep_table(*CUSTOM_TABLE)           # Program the microstep table, (mslut 8 words, mslutsel, mslutstart) → None
                                                         # MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0
motor.disable()                                          # Power stage off, () → None
                                                         # drives ENN high; the motor freewheels
