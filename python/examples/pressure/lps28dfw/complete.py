from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps28dfw import LPS28DFWFull

connection = I2CConnection(0x5C)
lps = LPS28DFWFull(connection)                                # Create LPS28DFW driver, (connection)
cid = lps.chip_id()                                           # Read chip ID, () → int
                                                             # returns 0xB4 for LPS28DFW
lps.configure(odr=0x04, avg=0x02, fs_mode=0, lpf_en=True, lpf_cfg=0)  # Configure chip, (odr 0–8, avg 0–7, fs_mode 0/1, lpf_en bool, lpf_cfg 0/1) → None
                                                             # sets output data rate, averaging, full-scale, IIR filter
lps.set_threshold(1050.0, high=True, low=True)                # Set pressure threshold, (threshold_hpa, high, low) → None
                                                             # arms PH/PL when pressure crosses threshold_hPa
lps.set_offset(0.5)                                           # Set one-point calibration, (offset_hpa) → None
                                                             # subtracts 0.5 hPa from subsequent readings
ready = lps.is_data_ready()                                   # Check data ready, () → bool
                                                             # reads STATUS.P_DA
vals = lps.read()                                             # Read both values, () → dict
                                                             # burst-reads pressure+temperature
lps.softreset()                                               # Soft reset, () → None
                                                             # waits ~2 ms for reboot
lps.fifo_configure(mode=1, wtm=16, stop_on_wtm=True)          # Configure FIFO, (mode 0–6, wtm 0–127, stop_on_wtm bool) → None
                                                             # enables 16-sample watermark FIFO
level = lps.fifo_level()                                      # FIFO unread count, () → int
samples = lps.fifo_read(level)                                # Drain FIFO, (count) → list[float] hPa
os_data = lps.read_oneshot()                                  # One-shot read, () → dict
                                                             # triggers a single measurement with ODR=0
print('chip=0x{:02X}, ready={}, vals={}, fifo={}, os={}'.format(cid, ready, vals, level, os_data))
print('===DONE: 0 passed, 0 failed===')