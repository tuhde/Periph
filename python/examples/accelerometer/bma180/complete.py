"""BMA180 complete example — exercise every Full-class API at least once.
Tier-1 signature comments + Tier-2 explanation on every call."""

from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.bma180 import BMA180Full
from machine import I2C, Pin

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)
connection = I2CConnection(i2c, 0x40)
chip = BMA180Full(connection)                          # Create BMA180 full driver, (connection)

x, y, z = chip.read()                                 # Read 3-axis acceleration, () → tuple g
                                                     # reads three X/Y/Z counts and converts to g (4096 LSB/g at ±2 g)
print("default: x={:.3f} y={:.3f} z={:.3f}".format(x, y, z))

raw = chip.read_raw()                                 # Read raw acceleration, () → tuple int counts
                                                     # burst-reads six LSB-then-MSB bytes, returns signed 14-bit counts
print("raw: x={} y={} z={}".format(*raw))

t = chip.read_temperature()                           # Read temperature, () → float °C
                                                     # reads 8-bit register, returns 25.0 + (signed - 2) * 0.5 °C
print("temperature: {:.2f} C".format(t))

al_v, ml_v = chip.read_version()                      # Read version, () → tuple int
                                                     # splits VERSION register into (al_version, ml_version) nibbles
print("version: al={} ml={}".format(al_v, ml_v))

ok = chip.new_data_available()                        # Check new data, () → bool
                                                     # reads the new_data bits of the three LSB registers
print("new_data_available:", ok)

chip.set_range(8)                                     # Set range, (range_g=8 g) → None
                                                     # range bits in OFFSET_LSB1 (0x35) bits 3:1 — code 0x0A = ±8 g
chip.set_bandwidth(40)                                # Set bandwidth, (bandwidth_hz=40 Hz) → None
                                                     # bw bits in BW_TCS (0x20) bits 7:4 — nearest low-pass value
chip.set_filter_mode(1)                               # Set filter mode, (mode=1 high-pass 1 Hz) → None
                                                     # bw code 1000 selects the high-pass filter
chip.set_mode(0)                                      # Set mode, (mode=0 low-noise) → None
                                                     # mode_config in TCO_Z (0x30) bits 1:0
chip.set_resolution(14)                               # Set resolution, (bits=14) → None
                                                     # 14-bit readout (offset_t 0x37) bit 0 cleared

chip.set_shadow(False)                                # Set shadow, (enabled=False) → None
                                                     # shadow_dis bit in GAIN_Y (0x33) — False enforces LSB-then-MSB
chip.set_sample_skip(False)                           # Set sample skip, (enabled=False) → None
                                                     # smp_skip bit in OFFSET_LSB1 (0x35) bit 0

chip.set_low_g(0.3, 40, 0.05, 0x07, 0, True)          # Configure low-g, (threshold_g=0.3, duration_ms=40, hysteresis_g=0.05, axes=0x07, counter=0, filtered=True) → None
                                                     # writes low_th, low_dur, low_hy; enables SOURCE_LOW_G
chip.set_high_g(1.8, 20, 0.1, 0x07, 0, True)          # Configure high-g, (threshold_g=1.8, duration_ms=20, hysteresis_g=0.1, axes=0x07, counter=0, filtered=True) → None
                                                     # writes high_th, high_dur, high_hy; enables SOURCE_HIGH_G
chip.set_slope(0.3, 3, 0x07, True)                    # Configure slope, (threshold_g=0.3, samples=3, axes=0x07, filtered=True) → None
                                                     # writes slope_th, slope_dur; enables SOURCE_SLOPE (exclusive with alert)
chip.set_alert(False)                                 # Set alert, (enabled=False) → None
                                                     # slope_alert off; clears SOURCE_ALERT
chip.set_tap(0.5, 250, 0x07, True)                    # Configure tap, (threshold_g=0.5, window_ms=250, axes=0x07, filtered=True) → None
                                                     # writes tapsens_th, tapsens_dur; enables SOURCE_TAP

chip.set_latch(True)                                  # Set latch, (enabled=True) → None
                                                     # lat_int bit in CTRL_REG3 (0x21) — clears by reset_int

chip.set_wake_up(True, 80)                             # Set wake up, (enabled=True, pause_ms=80) → None
                                                     # wake_up bit in GAIN_Z (0x34); wake_up_dur in TCO_Y (0x2F)

s1, s2, s3, s4 = chip.read_status()                  # Read status, () → tuple int
                                                     # reads STATUS_REG1..4 — latched source flags + axis signs
print("status: s1={} s2={} s3={} s4={}".format(s1, s2, s3, s4))

sign = chip.read_sign()                               # Read sign, () → dict
                                                     # decodes axis-sign bits for low/high/tapsens (s2/s4)
print("sign:", sign)

flags = chip.poll_interrupt()                         # Poll interrupt, () → int
                                                     # reads STATUS_REG3 — latched flags + first-axis
print("poll_interrupt:", hex(flags))

chip.clear_interrupt()                                # Clear interrupt, () → None
                                                     # writes reset_int to CTRL_REG0 (0x0D)

chip.disable_interrupt(BMA180Full.SOURCE_LOW_G)       # Disable interrupt, (source=SOURCE_LOW_G) → None
                                                     # clears low_int bit in CTRL_REG3
chip.disable_interrupt(BMA180Full.SOURCE_HIGH_G)      # Disable interrupt, (source=SOURCE_HIGH_G) → None
                                                     # clears high_int bit in CTRL_REG3
chip.disable_interrupt(BMA180Full.SOURCE_SLOPE)       # Disable interrupt, (source=SOURCE_SLOPE) → None
                                                     # clears slope_int bit in CTRL_REG3
chip.disable_interrupt(BMA180Full.SOURCE_TAP)         # Disable interrupt, (source=SOURCE_TAP) → None
                                                     # clears tap_int bit in CTRL_REG3

chip.enable_interrupt(BMA180Full.SOURCE_NEW_DATA)     # Enable interrupt, (source=SOURCE_NEW_DATA) → None
                                                     # sets new_data_int bit in CTRL_REG3
chip.disable_interrupt(BMA180Full.SOURCE_NEW_DATA)    # Disable interrupt, (source=SOURCE_NEW_DATA) → None
                                                     # clears new_data_int bit in CTRL_REG3

def _on_int(status):
    print("INT! status={}".format(hex(status)))
chip.on_interrupt(_on_int)                            # Subscribe to INT, (callback) → None
                                                     # INT pin rising → callback(_on_int)

chip.off_interrupt()                                  # Unsubscribe from INT, () → None
                                                     # detaches the handler from the INT pin

cd1 = chip.read_customer(0)                           # Read customer byte, (index=0) → int
                                                     # reads CD1 (0x2C) scratch byte
chip.write_customer(1, 0xA5)                          # Write customer byte, (index=1, value=0xA5) → None
                                                     # writes CD2 (0x2D) scratch byte

chip.calibrate_offset(0x07, 1)                        # Calibrate offset, (axes=0x07, mode=1 fine) → None
                                                     # in-field zero-g calibration (volatile, not written to EEPROM)

chip.sleep()                                          # Sleep, () → None
                                                     # sets sleep bit in CTRL_REG0 (0x0D) — no further bus access
chip.wake()                                           # Wake-up, () → None
                                                     # clears sleep bit; waits 2 ms for analog to settle