from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.lm25066 import LM25066Full
import time

connection = I2CConnection(0x40)                                       # Create I²C connection, (addr=0x40) → Connection
hotswap = LM25066Full(connection, r_sense_mohm=1.0, current_limit_high=False)  # Create LM25066 driver, (connection, r_sense_mohm, current_limit_high) → None

print(hotswap.voltage_in())                                            # Input voltage, () → float V
print(hotswap.voltage_out())                                           # Output voltage, () → float V
print(hotswap.current())                                               # Input current, () → float A
print(hotswap.power())                                                 # Input power, () → float W
print(hotswap.temperature())                                           # MOSFET temperature, () → float °C
print(hotswap.voltage_aux())                                           # VAUX pin voltage, () → float V (0 ... 1.16)

print(hotswap.read_block())                                            # Time-aligned BLOCK_READ, () → dict
                                                                       # diagnostic, current, voltage_out, voltage_in, power, temperature
print(hotswap.read_average())                                          # AVG_BLOCK_READ, () → dict
print(hotswap.black_box())                                             # Snapshot at first SMBA, () → dict
print(hotswap.voltage_in_avg(), hotswap.voltage_out_avg())             # Averaged VIN / VOUT, () → float V each
print(hotswap.current_avg(), hotswap.power_avg())                      # Averaged IIN / PIN, () → float each
print(hotswap.power_peak())                                            # Peak input power, () → float W
hotswap.clear_power_peak()                                             # Reset the peak register, () → None

hotswap.set_averaging(6)                                               # Average 2^6 samples, (exponent 0-12) → None
print(hotswap.get_averaging())                                         # () → int

hotswap.set_vin_uv_warn(10.0)                                          # VIN under-voltage warning, (volts | None) → None
hotswap.set_vin_ov_warn(15.0)                                          # VIN over-voltage warning, (volts | None) → None
hotswap.set_vout_uv_warn(9.0)                                          # VOUT under-voltage warning, (volts | None) → None
hotswap.set_ot_warn(100.0)                                             # Over-temperature warning, (celsius | None) → None
hotswap.set_ot_fault(125.0)                                            # Over-temperature fault, (celsius | None) → None
hotswap.set_iin_warn(20.0)                                             # Input over-current warning, (amps | None) → None
hotswap.set_pin_warn(200.0)                                            # Input over-power warning, (watts | None) → None
print(hotswap.get_limits())                                            # All thresholds, () → dict (None = disabled)

print(hex(hotswap.status_byte()), hex(hotswap.status_word()))          # STATUS_BYTE / STATUS_WORD, () → int each
print(hex(hotswap.status_vout()), hex(hotswap.status_input()))         # Per-group status bytes, () → int each
print(hex(hotswap.status_temperature()), hex(hotswap.status_cml()), hex(hotswap.status_mfr()))
print(hex(hotswap.diagnostic_word()))                                  # READ_DIAGNOSTIC_WORD, () → int
hotswap.clear_faults()                                                 # CLEAR_FAULTS (does not turn the MOSFET on), () → None

print(hotswap.is_on())                                                 # MOSFET on, () → bool
# hotswap.operation_off()                                              # CUTS THE LOAD RAIL, () → None
# hotswap.operation_on()                                               # Switch the MOSFET on, () → None

print(hex(hotswap.device_setup()))                                     # Read DEVICE_SETUP, () → int
# hotswap.configure_device(retry_code=7, current_limit_high=False, cb_ratio_high=None)
                                                                       # Override RETRY/CL/CB pins, (retry_code, current_limit_high, cb_ratio_high) → None
# hotswap.set_gate_mask(0x04)                                          # DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → None
print(hex(hotswap.get_gate_mask()))                                    # () → int
print(hex(hotswap.capability()))                                       # CAPABILITY, () → int

print(hotswap.manufacturer_id(), hotswap.model(), hotswap.revision())  # MFR_ID / MFR_MODEL / MFR_REVISION, () → str each

hotswap.enable_interrupt(LM25066Full.SOURCE_VIN_UV_WARN)               # Unmask an alert source, (source) → None
hotswap.disable_interrupt(LM25066Full.SOURCE_VIN_UV_WARN)              # Mask an alert source, (source) → None
print(hex(hotswap.poll_interrupt()))                                   # Diagnostic word, () → int
hotswap.on_interrupt(lambda diag: print('alert', hex(diag)))           # Subscribe to SMBA, (callback, int_pin=None) → None
time.sleep(2)
hotswap.off_interrupt()                                                # Unsubscribe, () → None
