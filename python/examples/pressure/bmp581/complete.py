from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp581 import BMP581Full

connection = I2CConnection(0x46)
bmp = BMP581Full(connection)                             # Create BMP581 driver, (connection, bus_type='i2c')
cid = bmp.chip_id()                                      # Read chip ID, () → int
                                                        # returns 0x50 for BMP581
bmp.configure(odr=0x1C, osr_p=0, osr_t=0, press_en=True)  # Configure chip, (odr=1Hz 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en bool) → None
                                                        # writes OSR_CONFIG and ODR_CONFIG atomically
bmp.set_mode(BMP581Full.MODE_NORMAL)                     # Set power mode, (mode 0/1/2/3) → None
                                                        # MODE_NORMAL: ODR-based duty-cycled autonomous mode
bmp.set_iir_filter(BMP581Full.IIR_COEFF_3, BMP581Full.IIR_BYPASS)  # Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → None
                                                        # suppresses short-term pressure disturbances on the data registers
bmp.configure_fifo(BMP581Full.FIFO_BOTH, mode=BMP581Full.FIFO_STREAM, threshold=8)  # Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → None
                                                        # store 8-frame batches of pressure+temperature samples
n = bmp.fifo_count()                                     # Read FIFO frame count, () → int
                                                        # returns number of frames currently buffered
bmp.enable_drdy_interrupt(True)                          # Enable data-ready interrupt, (enable bool) → None
bmp.data_ready()                                         # Check data ready, () → bool
                                                        # reads INT_STATUS (clear-on-read) and returns the drdy bit
p, t = bmp.forced()                                      # Trigger FORCED measurement, () → (Pa, °C)
                                                        # forces a single conversion and waits for completion
p2, t2 = bmp.both()                                      # Read both atomically, () → (Pa, °C)
                                                        # single burst read of TEMP_XLSB..PRESS_MSB
alt = bmp.altitude()                                     # Compute altitude, (sea_level_pa=101325.0) → float m
                                                        # barometric formula against the reference pressure
status = bmp.status()                                    # Read STATUS, () → int
                                                        # raw status byte; bit 1 = nvm_rdy, bit 2 = nvm_err
istatus = bmp.interrupt_status()                         # Read INT_STATUS, () → int
                                                        # clear-on-read; returns raw byte
osr_p, osr_t = bmp.effective_osr()                       # Read effective OSR, () → (osr_p 0–7, osr_t 0–7)
                                                        # actual OSR in use after any auto-reduction
bmp.set_oor_threshold(threshold_pa=110000, range_pa=200, count_limit=1)  # Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → None
                                                        # fires INT_STATUS.oor_p when pressure leaves threshold +/- range_pa
bmp.software_reset()                                     # Soft reset chip, () → None
                                                        # issues CMD=0xB6 and re-runs the init sequence
print('P={} Pa, T={} C, alt={} m, frames={}'.format(p, t, alt, n))
print('===DONE: 0 passed, 0 failed===')