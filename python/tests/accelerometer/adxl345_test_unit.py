"""Unit test for the ADXL345 — runs without hardware using the I2C mock.

Verifies the driver's register read/write sequencing, scale conversion,
and SPI / I²C address-byte framing by observing what the driver writes
to the mock connection.
"""

import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.adxl345 import ADXL345Minimal, ADXL345Full

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


# Preload DEVID = 0xE5 so init's WHO_AM_I check passes.
mock = I2CConnectionMock()
mock.set_register(ADXL345Minimal._REG_DEVID, 0xE5)
# Preload a known 6-byte burst at DATAX0: x=0x0001 (1 LSB), y=0x0002, z=0x0003
# in little-endian — i.e. raw_x=1, raw_y=2, raw_z=3.
mock.set_register(ADXL345Minimal._REG_DATAX0,
                  0x01, 0x00,   # raw_x = 1
                  0x02, 0x00,   # raw_y = 2
                  0x03, 0x00)   # raw_z = 3

accel = ADXL345Minimal(mock)
check_true('construct_minimal', True)

# Init writes DATA_FORMAT=0x08, BW_RATE=0x0A, POWER_CTL=0x08.
writes = mock.writes
df_writes = [w for w in writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_DATA_FORMAT]
check_true('init_writes_data_format_default', df_writes and df_writes[0][1] == 0x08)

bw_writes = [w for w in writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_BW_RATE]
check_true('init_writes_bw_rate_default', bw_writes and bw_writes[0][1] == 0x0A)

pwr_writes = [w for w in writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_POWER_CTL]
check_true('init_writes_power_ctl_default', pwr_writes and pwr_writes[0][1] == 0x08)

# Verify init also reads DEVID for the identity check.
devid_reads = [w for w in writes if len(w) == 1 and w[0] == ADXL345Minimal._REG_DEVID]
check_true('init_reads_devid', len(devid_reads) >= 1)

# read() should compute (1, 2, 3) * 3.9e-3 = (0.0039, 0.0078, 0.0117) g.
x, y, z = accel.read()
check_true('read_x', abs(x - 0.0039) < 1e-9)
check_true('read_y', abs(y - 0.0078) < 1e-9)
check_true('read_z', abs(z - 0.0117) < 1e-9)

# Full range switch to ±4 g: DATA_FORMAT bit 0 should toggle.
mock2 = I2CConnectionMock()
mock2.set_register(ADXL345Minimal._REG_DEVID, 0xE5)
mock2.set_register(ADXL345Minimal._REG_DATA_FORMAT, 0x08)
mock2.set_register(ADXL345Minimal._REG_DATAX0, 0x00, 0x01, 0x00, 0x02, 0x00, 0x03)

accel_full = ADXL345Full(mock2)
accel_full.set_range(4)
df_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_DATA_FORMAT]
check_true('set_range_4g', df_writes and df_writes[-1][1] == (0x08 | 0x01))

# Data-rate selection: requesting 100 Hz should leave BW_RATE = 0x0A.
mock2.set_register(ADXL345Minimal._REG_BW_RATE, 0x0A)
accel_full.set_data_rate(100)
bw_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_BW_RATE]
check_true('set_data_rate_100hz', bw_writes and bw_writes[-1][1] == 0x0A)

# Low-power toggles bit 4 of BW_RATE.
accel_full.set_low_power(True)
bw_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_BW_RATE]
check_true('set_low_power', bw_writes and (bw_writes[-1][1] & 0x10) == 0x10)

# Offset encoding: 0.5 g → 32 LSB at 15.6 mg/LSB; 0x20 stored.
accel_full.set_offset(0.5, -0.5, 0.0)
ofsx_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_OFSX]
ofsy_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_OFSY]
ofsz_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_OFSZ]
check_true('set_offset_x',  ofsx_writes and ofsx_writes[-1][1] == 32)
check_true('set_offset_y_signed', ofsy_writes and ofsy_writes[-1][1] == (224 & 0xFF))  # -32 → 0xE0
check_true('set_offset_z_zero', ofsz_writes and ofsz_writes[-1][1] == 0)

# Interrupt enable: set_interrupt(INT_WATERMARK, True, 1) → bit 1 of INT_ENABLE.
mock2.set_register(ADXL345Minimal._REG_INT_ENABLE, 0x00)
mock2.set_register(ADXL345Minimal._REG_INT_MAP, 0x00)
accel_full.set_interrupt(ADXL345Full.INT_WATERMARK, True, 1)
ie_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_INT_ENABLE]
check_true('enable_watermark_on_int1', ie_writes and (ie_writes[-1][1] & 0x02) == 0x02)

# Single-tap threshold: 0.5 g / 62.5 mg = 8 LSB.
accel_full.set_tap_detection(0.5, 10.0)
thresh_tap = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_THRESH_TAP]
check_true('set_tap_threshold', thresh_tap and thresh_tap[-1][1] == 8)

# Free-fall threshold: 0.3 g / 62.5 mg = 4.8 → 5 LSB.
accel_full.set_free_fall(0.3, 100)
ff_thresh = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_THRESH_FF]
ff_time = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_TIME_FF]
check_true('set_free_fall_threshold', ff_thresh and ff_thresh[-1][1] == 5)
check_true('set_free_fall_time', ff_time and ff_time[-1][1] == 20)

# Sleep mode: 8 Hz wakeup → POWER_CTL has Sleep bit set (0x04).
accel_full.set_sleep(True)
pwr_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_POWER_CTL]
check_true('set_sleep_true', pwr_writes and (pwr_writes[-1][1] & 0x04) == 0x04)

accel_full.set_sleep(False)
pwr_writes = [w for w in mock2.writes if len(w) == 2 and w[0] == ADXL345Minimal._REG_POWER_CTL]
check_true('set_sleep_false', pwr_writes and (pwr_writes[-1][1] & 0x04) == 0x00)

# SPI transport: command byte framing is R/W|MB|A5..A0. The I2C mock's
# write_read() looks up registers[byte[0]] verbatim, so for SPI we preload
# at the SPI command-byte value (reg | 0x80 for single reads, reg | 0xC0
# for burst reads where MB=1).
SPI_RD = 0x80
SPI_BURST_RD = 0xC0

mock3 = I2CConnectionMock()
mock3.set_register(ADXL345Minimal._REG_DEVID | SPI_RD, 0xE5)
mock3.set_register(ADXL345Minimal._REG_DATAX0 | SPI_BURST_RD,
                   0x00, 0x01, 0x00, 0x02, 0x00, 0x03)

accel_spi = ADXL345Minimal(mock3, bus_type='spi')
check_true('spi_construct_minimal', True)

# SPI write: command byte = reg (read=0, MB=0) for single-byte writes.
spi_data_format_writes = [w for w in mock3.writes
                          if len(w) == 2 and w[0] == ADXL345Minimal._REG_DATA_FORMAT]
check_true('spi_init_writes_data_format', spi_data_format_writes
           and spi_data_format_writes[0][1] == 0x08)

# SPI burst read uses MB=1 (0x40): command byte = reg | 0x80 | 0x40.
# Force a burst read first so the mock records the SPI command byte.
accel_spi.read()
burst_reads = [w for w in mock3.writes
               if len(w) == 1 and (w[0] & 0xC0) == 0xC0]
check_true('spi_burst_read_sets_mb_bit',
           len(burst_reads) >= 1 and (burst_reads[-1][0] & 0x40) == 0x40)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)