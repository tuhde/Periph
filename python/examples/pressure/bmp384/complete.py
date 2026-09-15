from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.bmp384 import BMP384Full

connection = I2CConnection(0x76)
bmp = BMP384Full(connection)                             # Create BMP384 driver, (connection, bus_type='i2c')

bmp.configure(osr_p=4, osr_t=1, iir_filter=2, odr_sel=0x03)  # Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
                                                             # sets oversampling, IIR coefficient, and output data rate; validates ODR ≥ T_conv
bmp.set_mode(BMP384Full.MODE_NORMAL)                    # Set power mode, (mode 0/1/3) → None
ready = bmp.is_data_ready()                              # Check data-ready flag, () → bool
                                                          # true if STATUS.drddy_press is set
t = bmp.temperature()                                    # Read temperature, () → float °C
p = bmp.pressure()                                       # Read pressure, () → float hPa
reading = bmp.read()                                     # Read both values in one burst, () → dict {pressure, temperature}
forced = bmp.read_forced()                               # Trigger forced measurement and read, () → dict {pressure, temperature}
bmp.fifo_configure(press_en=True, temp_en=True, wtm=64)  # Configure FIFO, (press_en bool, temp_en bool, wtm 0–511, stop_on_full=False) → None
                                                          # enables FIFO, sets watermark, arms pressure+temperature frames
frames = bmp.fifo_read()                                 # Read and parse FIFO frames, () → list of dict {type, value}
bmp.fifo_flush()                                         # Flush FIFO contents, () → None
bmp.softreset()                                          # Soft reset chip, () → None
                                                          # writes 0xB6 to CMD, waits 2 ms, re-reads calibration
print('T={} C, P={} hPa, ready={}, frames={}'.format(t, p, ready, len(frames)))
print('===DONE: 0 passed, 0 failed===')
