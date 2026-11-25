"""Complete example for the TMC5160 — exercise every method in the public API.

Constructs the Full driver over SPI, tunes current, ramp, reference switches,
chopper / StealthChop, StallGuard2 / CoolStep, DcStep, the encoder and the
gate driver, moves with the ramp generator and in direct-current mode, and
reads back every status register. The UART-only methods raise on SPI and are
shown at the end.
"""

import time
from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.connection.output_pin import MicroPythonOutputPin
from periph.connection.input_pin import MicroPythonPin
from periph.chips.motor.tmc5160 import TMC5160Full

spi = SPI(1, baudrate=1_000_000, polarity=1, phase=1)
connection = SPIConnection(spi, Pin(5, Pin.OUT))
motor = TMC5160Full(connection, r_sense_ohm=0.075, en_pin=MicroPythonOutputPin(4),
                    diag0_pin=MicroPythonPin(Pin(19, Pin.IN)),
                    diag1_pin=MicroPythonPin(Pin(20, Pin.IN)))  # Create TMC5160 Full driver, (connection, bus_type='spi', r_sense_ohm=0.075 Ω, en_pin, address=0, echo=True, multi_node=False, fclk_hz=12000000 Hz, diag0_pin, diag1_pin)
                                                         # reads IOIN to verify the chip, writes the default configuration with verified writes

motor.set_current(1.5)                                   # Set run current, (rms_a A RMS) → None
                                                         # sets GLOBALSCALER and IRUN; hold current becomes a third
motor.set_hold_current(0.3)                              # Set hold current, (rms_a A RMS) → None
                                                         # overrides the automatic one-third hold current
motor.set_global_scaler(200)                             # Set raw GLOBALSCALER, (value 0 or 32–256) → None
                                                         # 32/256 … 256/256 of the maximum current
motor.set_current_scale(31, 10, 6)                       # Set raw IHOLD_IRUN, (irun 0–31, ihold 0–31, ihold_delay 0–15) → None
                                                         # direct register fields
motor.set_power_down_delay(20)                           # Set TPOWERDOWN, (tpowerdown 0–255) → None
                                                         # units of 2^18 clocks
motor.set_speed(300)                                     # Set target speed, (full_steps_per_s) → None
                                                         # VMAX
motor.set_acceleration(800)                              # Set acceleration, (full_steps_per_s2) → None
                                                         # A1 = AMAX = DMAX = D1
motor.configure_ramp(0, 1200, 200, 600, 500, 900, 1500, 10)  # Configure the SixPoint ramp, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → None
                                                         # physical units: full steps/s and full steps/s²
motor.set_ramp_raw(0, 1564, 0, 1564, 71583, 1564, 1564, 10)  # Configure the ramp in raw units, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → None
                                                         # register values
motor.set_zero_wait(0.1)                                 # Set TZEROWAIT, (seconds) → None
                                                         # wait after ramp-down to zero
motor.set_microsteps(256)                                # Set microstep resolution, (n 1–256) → None
                                                         # 256 is the normal setting with the motion controller
motor.set_interpolation(False)                           # Interpolation, (enabled) → None
                                                         # only useful for external STEP/DIR
motor.set_chopper_mode(False)                            # Choose chopper, (spreadcycle) → None
                                                         # False enables StealthChop below TPWMTHRS
motor.set_stealthchop_threshold(500)                     # Set StealthChop velocity limit, (tpwmthrs 20 bit) → None
                                                         # faster than this the chip switches to SpreadCycle
motor.set_high_velocity_threshold(0)                     # Set THIGH, (thigh 20 bit) → None
                                                         # 0 keeps the high-velocity features off
motor.configure_chopper(3, 2, 4, 1, 0)                   # Configure SpreadCycle timing, (toff, tbl, hstrt, hend, tpfd=0) → None
                                                         # direct CHOPCONF fields with chm = 0
motor.configure_constant_off_time(3, 2, 4, 0, False, 0)  # Configure constant off time, (toff, tbl, tfd, offset, disfdcc, tpfd=0) → None
                                                         # chm = 1 with fast decay
motor.configure_chopper(3, 2, 4, 1, 0)                   # Back to SpreadCycle, (toff, tbl, hstrt, hend, tpfd=0) → None
                                                         # chm = 0 again
motor.configure_high_velocity(False, False)              # Behaviour above THIGH, (vhighchm, vhighfs) → None
                                                         # both off
motor.configure_stealthchop(True, True, 30, 0, 0, 4, 12)  # Configure StealthChop, (autoscale, autograd, pwm_ofs, pwm_grad, pwm_freq, pwm_reg, pwm_lim) → None
                                                         # direct PWMCONF fields
motor.set_freewheel(0)                                   # Set standstill mode, (mode 0–3) → None
                                                         # PWMCONF.freewheel, only effective when IHOLD is 0
motor.set_short_protection(True, True)                   # Short protections, (short_gnd, short_supply) → None
                                                         # True keeps the protection enabled
motor.set_double_edge(False)                             # Step on both edges, (enabled) → None
                                                         # external STEP/DIR only
motor.set_direction_inverted(False)                      # Invert direction, (inverted) → None
                                                         # GCONF.shaft
motor.set_fast_standstill(False)                         # Fast standstill timeout, (enabled) → None
                                                         # GCONF.faststandstill
motor.set_recalibrate(False)                             # Zero-crossing recalibration, (enabled) → None
                                                         # GCONF.recalibrate
motor.set_small_hysteresis(False)                        # Comparison hysteresis, (enabled) → None
                                                         # False = 1/16
motor.set_emergency_stop(False)                          # ENCA_DCIN emergency stop, (enabled) → None
                                                         # GCONF.stop_enable
motor.configure_diag(False, False, False, False)         # DIAG pin function, (diag0_step_output, diag1_dir_output, diag0_pushpull, diag1_pushpull) → None
                                                         # DIAG0 = interrupt, DIAG1 = position compare
motor.configure_switches(True, True, False, False, False, False)  # Configure reference switches, (stop_left, stop_right, left_active_low, right_active_low, swap, soft_stop) → None
                                                         # automatic stop on both switches
motor.configure_latch(True, False, True, False, False)   # Configure position latching, (left_active, left_inactive, right_active, right_inactive, latch_encoder) → None
                                                         # latch XACTUAL on active-going switch edges
motor.set_position_compare(100)                          # Set X_COMPARE, (position full steps) → None
                                                         # DIAG1 high while XACTUAL = X_COMPARE
motor.set_stall_threshold(0)                             # Set StallGuard2 threshold, (sgt -64..63) → None
                                                         # lower = more sensitive
motor.set_stallguard_filter(False)                       # StallGuard2 filter, (enabled) → None
                                                         # COOLCONF.sfilt
motor.set_coolstep_threshold(1000)                       # Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → None
                                                         # active while TCOOLTHRS >= TSTEP >= THIGH
motor.configure_coolstep(5, 2, 1, 1, 0)                  # Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → None
                                                         # semin = 0 would disable CoolStep
motor.enable_stall_stop(False)                           # Automatic stop on stall, (enabled) → None
                                                         # SW_MODE.sg_stop; needs TCOOLTHRS first
motor.set_dcstep(0, 0, 0)                                # Configure DcStep, (vdcmin, dc_time, dc_sg) → None
                                                         # all zero keeps DcStep off
motor.configure_encoder(False, False, False, False, False, False, False, False, False, False, False)  # Configure the ABN encoder, (pol_a, pol_b, pol_n, ignore_ab, clr_cont, clr_once, pos_edge, neg_edge, clr_enc_x, latch_x_act, decimal) → None
                                                         # ENCMODE bits
motor.set_encoder_constant(1.0)                          # Set ENC_CONST, (value signed 16.16) → None
                                                         # encoder counts to microsteps
motor.set_encoder_position(0)                            # Set X_ENC, (counts) → None
                                                         # encoder position
motor.set_encoder_deviation(0)                           # Set ENC_DEVIATION, (steps 0–2^20-1) → None
                                                         # 0 = off
motor.configure_gate_driver(0, 4, 0, 0, 0)               # Configure DRV_CONF, (bbmtime, bbmclks, otselect, drvstrength, filt_isense) → None
                                                         # board-specific: wrong values can destroy the MOSFETs
motor.configure_short_detection(6, 6, 1, 0)              # Configure SHORT_CONF, (s2vs_level, s2g_level, shortfilter, shortdelay) → None
                                                         # board-specific

motor.enable()                                           # Power stage on, () → None
                                                         # drives DRV_ENN low, waits 150 ms
motor.set_position(0)                                    # Define the position, (position full steps) → None
                                                         # writes XACTUAL and XTARGET
motor.move_to(200)                                       # Positioning move, (position full steps) → None
                                                         # non-blocking
print('reached', motor.wait_until_reached())             # Wait for the target, (timeout_s=None) → bool
                                                         # polls RAMP_STAT
print('position', motor.position())                      # Read actual position, () → float full steps
                                                         # XACTUAL
print('velocity', motor.velocity())                      # Read actual velocity, () → float full steps/s
                                                         # VACTUAL
print('is_reached', motor.is_position_reached())         # Position reached flag, () → bool
                                                         # RAMP_STAT.position_reached
motor.move(-200, 400)                                    # Blocking relative move, (full_steps, full_steps_per_s=None) → None
                                                         # optional speed override
motor.run(150)                                           # Velocity mode, (full_steps_per_s) → None
                                                         # sign = direction
time.sleep(1)
motor.set_ramp_mode(1)                                   # Set RAMPMODE, (mode 0–3) → None
                                                         # 1 = velocity mode to +VMAX
motor.hold()                                             # Hold mode, () → None
                                                         # keep the present velocity
motor.stop()                                             # Controlled stop, () → None
                                                         # VMAX = 0
motor.wait_until_reached()                               # Wait for standstill, (timeout_s=None) → bool
                                                         # vzero
motor.set_target_raw(51200)                              # Write XTARGET raw, (xtarget microsteps) → None
                                                         # starts positioning in mode 0
motor.set_ramp_mode(0)                                   # Back to positioning, (mode) → None
                                                         # RAMPMODE = 0
print('ramp', motor.read_ramp_status())                  # Read RAMP_STAT, () → dict
                                                         # decoded ramp and switch flags
motor.clear_ramp_events()                                # Clear event flags, () → None
                                                         # write-1-to-clear bits
print('switches', motor.read_switches())                 # Read switch states, () → (bool, bool)
                                                         # (left, right)
print('latch', motor.read_latched_position())            # Read XLATCH, () → float full steps
                                                         # position at the last switch event
print('tstep', motor.read_tstep())                       # Read step interval, () → int clocks
                                                         # time between 1/256 microsteps
print('position', motor.microstep_position())            # Read microstep counter, () → int 0–1023
                                                         # MSCNT
print('currents', motor.read_coil_currents())            # Read coil currents, () → (int, int) table values
                                                         # signed (CUR_A, CUR_B) from MSCURACT
print('scale', motor.read_current_scale())               # Read actual current scale, () → int 0–31
                                                         # DRV_STATUS.CS_ACTUAL
print('sg_result', motor.read_stallguard())              # Read StallGuard2 result, () → int 0–1023
                                                         # SG_RESULT, 0 = highest load
print('stalled', motor.is_stalled())                     # Read stall flag, () → bool
                                                         # DRV_STATUS.StallGuard
print('diag0', motor.read_diag(0))                       # Read DIAG0 pin, (n 0 or 1) → bool
                                                         # needs the diag pin to be wired (SPI only)
print('pwm_scale', motor.read_pwm_scale())               # Read PWM_SCALE, () → (int, int)
                                                         # (PWM_SCALE_SUM, PWM_SCALE_AUTO)
print('pwm_auto', motor.read_pwm_auto())                 # Read PWM_AUTO, () → (int, int)
                                                         # (PWM_OFS_AUTO, PWM_GRAD_AUTO)
print('offset', motor.read_offset())                     # Read OFFSET_READ, () → (int, int)
                                                         # offset calibration of both phases
print('encoder', motor.read_encoder_position(), motor.read_encoder_latch(), motor.read_encoder_status())  # Read the encoder, () → (int, int, dict)
                                                         # X_ENC, ENC_LATCH, ENC_STATUS
motor.clear_encoder_status()                             # Clear encoder events, () → None
                                                         # write-1-to-clear
print('status', motor.read_status())                     # Read driver status, () → dict
                                                         # decoded DRV_STATUS
print('standstill', motor.is_standstill())               # Read standstill flag, () → bool
                                                         # DRV_STATUS.stst
print('fault', motor.read_fault())                       # Read fault bitmask, () → int
                                                         # FAULT_* constants, 0 = none
print('gstat', motor.read_global_status())               # Read GSTAT, () → dict
                                                         # reset, drv_err, uv_cp; not cleared by the read
motor.clear_global_status()                              # Clear GSTAT, () → None
                                                         # writes 0x07
print('ioin', motor.read_ioin())                         # Read IOIN, () → dict
                                                         # raw word, pin states and silicon version
print('gconf', hex(motor.read_register(0x00)))           # Read a raw register, (reg) → int
                                                         # any readable register
motor.write_register(0x13, 500, verify=True)             # Write a raw register, (reg, value, verify=False) → None
                                                         # here TPWMTHRS again, with the mirror check
motor.set_direct_mode(True)                              # Direct coil-current mode, (enabled) → None
                                                         # bypasses the ramp generator
motor.set_coil_currents(100, 0)                          # Write XDIRECT, (coil_a, coil_b -255..255) → None
                                                         # scaled by IHOLD
time.sleep(0.5)
motor.set_direct_mode(False)                             # Back to the ramp generator, (enabled) → None
                                                         # restores XTARGET
CUSTOM_TABLE = None                                      # replace with (mslut[8], mslutsel, mslutstart) to program a table
if CUSTOM_TABLE and motor.microstep_position() == 0:
    motor.write_microstep_table(*CUSTOM_TABLE)           # Program the microstep table, (mslut 8 words, mslutsel, mslutstart) → None
                                                         # MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0
for call in (lambda: motor.set_slave_address(0), lambda: motor.set_send_delay(2),
             lambda: motor.set_nao_polarity(True), motor.read_ifcnt):  # UART-only methods, (slaveaddr) / (sd) / (level) / () → None / None / None / int
    try:
        call()
    except RuntimeError:
        print('UART only')                               # set_slave_address, set_send_delay, set_nao_polarity and read_ifcnt need bus_type='uart'
motor.disable()                                          # Power stage off, () → None
                                                         # drives DRV_ENN high; the outputs float
