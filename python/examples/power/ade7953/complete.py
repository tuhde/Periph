"""ADE7953 complete example — exercise every method in the Full API.

Demonstrates the recommended bring-up sequence (chip ID, last-operation
diagnostics), calibration, accumulation modes, power-quality features
(sag, peak, overcurrent/overvoltage, zero-crossing), CF pulse outputs,
and interrupts. Reads every active measurement once and exits.
"""

from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.ade7953 import (
    ADE7953Full,
    ADE7953Source,
    ADE7953CFSource,
    ADE7953AltOutput,
)
import time


I2C_ADDR = 0x38
VOLTAGE_GAIN = 251.0
CURRENT_GAIN_A = 30.0
CURRENT_GAIN_B = 30.0


def main():
    connection = I2CConnection(I2C_ADDR)
    ade = ADE7953Full(connection, VOLTAGE_GAIN, CURRENT_GAIN_A)    # Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    # --- chip identification and bus diagnostics ---
    print('version:', hex(ade.version()))                           # Read silicon version, () → int
    print('last op:', ade.last_operation())                         # Read last bus operation, () → dict

    # --- Channel A primary measurements ---
    print('V=', ade.voltage())                                     # Read bus voltage, () → float V
                                                                     # converts raw VRMS to volts using voltage_gain
    print('I_a=', ade.current())                                   # Read load current, () → float A
                                                                     # converts raw IRMSA to amperes using current_gain
    print('P_a=', ade.active_power())                              # Read active power, () → float W
                                                                     # converts raw AWATT (instantaneous, 6.99 kHz) to watts
    print('E_a=', ade.active_energy())                             # Read active energy, () → float Wh
                                                                     # converts raw AENERGYA accumulated LSBs to watt-hours
    print('PF_a=', ade.power_factor())                             # Read power factor, () → float
                                                                     # converts raw PFA (1 LSB = 2^-15) to a −1.0 … +1.0 ratio
    print('angle_a=', ade.phase_angle(50.0))                       # Read phase angle, (line_frequency_hz=50) → float deg
    print('line_period=', ade.line_period())                       # Read line period, () → float s
    print('line_freq=', ade.line_frequency())                      # Read line frequency, () → float Hz

    # --- Channel B and reactive/apparent measurements ---
    ade.configure_channel_b(CURRENT_GAIN_B)                         # Set Channel B calibration, (current_gain_b) → None
    print('I_b=', ade.current_b())                                 # Read Current Channel B, () → float A
    print('P_b=', ade.active_power_b())                            # Read Channel B active power, () → float W
    print('Q_a=', ade.reactive_power())                            # Read reactive power, () → float VAR
    print('S_a=', ade.apparent_power())                             # Read apparent power, () → float VA
    print('E_Q=', ade.reactive_energy())                           # Read reactive energy, () → float VARh
    print('E_S=', ade.apparent_energy())                           # Read apparent energy, () → float VAh

    sample = ade.waveform_sample()                                  # Read 6.99 kHz snapshot, () → dict
    print('waveform:', sample)

    # --- calibration ---
    ade.set_pga('a', 1)                                             # Write PGA Channel A, (channel, gain) → None
                                                                     # gain 1..16 (+22 valid for Channel A only)
    ade.set_pga('v', 1)                                             # Write PGA voltage channel, (channel, gain) → None
    ade.set_phase_calibration('a', 0.0)                             # Write phase calibration A, (channel, delay_s) → None
                                                                     # negative delay_s advances; range ±383 × 1.117 µs
    ade.set_gain_calibration(0x282, 0x400000)          # Write active-power gain A, (register, value) → None
                                                                     # 0x400000 = unity; valid range 0x200000..0x600000
    ade.set_offset_calibration(0x289, 0)             # Write active-power offset A, (register, value) → None
                                                                     # signed 24-bit offset
    print('checksum:', hex(ade.checksum()))                         # Read CRC/checksum, () → int
    ade.enable_checksum(True)                                       # Enable CRC/checksum, (enabled) → None

    # --- accumulation modes ---
    ade.set_active_energy_mode('a', 'normal')                       # Set active-energy mode A, (channel, mode) → None
                                                                     # mode = 'normal' | 'positive_only' | 'absolute'
    ade.set_reactive_energy_mode('a', 'normal')                     # Set reactive-energy mode A, (channel, mode) → None
                                                                     # mode = 'normal' | 'antitamper' | 'absolute'
    ade.set_apparent_energy_mode('a', 'normal')                     # Set apparent-energy mode A, (channel, mode) → None
                                                                     # mode = 'normal' | 'ampere_hour'
    ade.configure_line_cycle_accumulation(('a_active',), 100)       # Configure line-cycle accumulation, (channels, half_cycles) → None
                                                                     # half_cycles: number of half-line-cycles (1–65535)
    ade.set_read_with_reset(True)                                   # Set energy-register reset-on-read, (enabled) → None
    print('power_sign:', ade.power_sign())                         # Read sign bits, () → dict
    print('no_load:', ade.no_load_status())                         # Read no-load status, () → dict

    # --- no-load / sag / peak / overcurrent / overvoltage ---
    ade.configure_no_load(active=0x00E419)                          # Configure no-load thresholds, (active=None, reactive=None, apparent=None) → None
                                                                     # raw 24-bit threshold per kind; None leaves it unchanged
    ade.disable_no_load(apparent=True)                              # Disable no-load features, (active=False, reactive=False, apparent=False) → None
    ade.configure_sag(10, 0x100000)                                 # Configure sag detection, (half_cycles, level) → None
                                                                     # half_cycles 0..255 (0 disables); level raw 24-bit
    print('Vpeak=', ade.peak_voltage())                            # Read peak voltage, () → float V
                                                                     # does NOT clear the underlying peak register
    print('Ipeak_a=', ade.peak_current_a())                         # Read peak Current Channel A, () → float A
                                                                     # does NOT clear the underlying peak register
    print('Ipeak_b=', ade.peak_current_b())                         # Read peak Current Channel B, () → float A
    ade.read_reset_peak_voltage()                                   # Read-and-reset peak voltage, () → float V
    ade.read_reset_peak_current_a()                                 # Read-and-reset peak Current Channel A, () → float A
    ade.configure_overvoltage(260.0)                                # Configure overvoltage, (threshold) → None
                                                                     # threshold in volts (same scale as voltage())
    ade.configure_overcurrent(40.0)                                 # Configure overcurrent, (threshold) → None
                                                                     # threshold in amperes; applies to BOTH current channels

    # --- zero-crossing, REVP, alternate outputs, CF pulses ---
    ade.configure_zero_crossing(timeout_half_cycles=0xFFFF)          # Configure zero-crossing, (timeout_half_cycles=0xFFFF, edge='both', current_channel='a') → None
                                                                     # edge = 'both' | 'positive' | 'negative'
    ade.configure_revp(pulse_mode=False, track_cf2=False)           # Configure REVP pin, (pulse_mode=False, track_cf2=False) → None
                                                                     # pulse_mode: level (False) vs. 1 Hz pulse (True)
    ade.configure_alt_output('zx', ADE7953AltOutput.SAG)            # Configure alternate output, (pin, function) → None
                                                                     # pin = 'zx' | 'zx_i' | 'revp'
    ade.configure_cf(1, ADE7953CFSource.ACTIVE_A, 0x3F)             # Configure CF1, (cf=1|2, source, denominator=0x3F) → None
                                                                     # denominator is written twice in succession (datasheet requirement)
    ade.disable_cf(1)                                               # Disable CF1, (cf) → None

    # --- interrupts ---
    ade.enable_interrupt(ADE7953Source.SAG)                         # Enable interrupt source, (source) → None
    ade.enable_interrupt(ADE7953Source.ZXV)                         # Enable voltage zero-crossing interrupt, (source) → None
    ade.enable_interrupt(ADE7953Source.OIA)                         # Enable Channel A overcurrent interrupt, (source) → None
    ade.enable_interrupt(ADE7953Source.OIB)                         # Enable Channel B overcurrent interrupt, (source) → None
    print('irq_a:', hex(ade.interrupt_status('a')))                 # Read interrupt status A, (group='a') → int
    print('irq_b:', hex(ade.interrupt_status('b')))                 # Read interrupt status B, (group='b') → int
    ade.clear_interrupts('a')                                       # Read-and-clear interrupts A, (group='a') → int
    ade.disable_interrupt(ADE7953Source.SAG)                        # Disable interrupt source, (source) → None

    # --- communication, reset ---
    ade.lock_communication_interface()                              # Lock communication interface, () → None
                                                                     # the first used interface becomes permanent
    ade.set_write_protection(False, False, False)                   # Set write protection, (protect_8bit, protect_16bit, protect_24_32bit) → None
                                                                     # the WRITE_PROTECT register itself remains writable
    ade.set_external_reference(False)                               # Set external reference, (enabled) → None
                                                                     # when enabled, an external 1.2 V reference must be applied to REF
    ade.reset()                                                     # Software reset, () → None
                                                                     # waits 110 ms then re-runs the mandatory power-up sequence
    time.sleep(0.2)


main()