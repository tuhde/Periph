from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps22df import LPS22DFFull

connection = I2CConnection(0x5C)
lps = LPS22DFFull(connection)                              # Create LPS22DF driver, (connection, bus_type='i2c')
lps.configure(odr=3, avg=0, en_lpfp=False, lfpf_cfg=0, bdu=True)  # Configure chip, (odr 0–8, avg 0–7, en_lpfp=False, lfpf_cfg=0/1, bdu=False/True) → None
                                                              # writes CTRL_REG1 and CTRL_REG2
lps.oneshot()                                              # Trigger one-shot conversion, () → None
                                                              # sets power-down then ONESHOT=1, waits for data
p = lps.pressure()                                         # Read pressure, () → float Pa
                                                              # 24-bit two's complement, 4096 LSB/hPa → Pa
t = lps.temperature()                                      # Read temperature, () → float °C
                                                              # 16-bit two's complement, 100 LSB/°C
alt = lps.altitude(101325.0)                               # Compute altitude, (sea_level_pa=101325.0) → float m
                                                              # uses barometric formula to convert pressure to metres
lps.software_reset()                                       # Reset chip, () → None
                                                              # self-clears SWRESET bit after <5 µs
lps.set_pressure_offset(-50.0)                             # Set pressure offset, (offset_pa=-50.0) → None
                                                              # one-point calibration in pascals; persists in NVM
lps.set_pressure_threshold(102000.0)                       # Set pressure threshold, (threshold_pa=102000.0) → None
                                                              # 15-bit unsigned; raises INT when pressure exceeds it
lps.configure_interrupt(int_h_l=False, pp_od=False, drdy=True, drdy_pls=False,  # Configure interrupt, (int_h_l=False, pp_od=False, drdy=False, drdy_pls=False, int_en=False, int_f_wtm=False, int_f_full=False, int_f_ovr=False) → None
                       int_en=True, int_f_wtm=False, int_f_full=False, int_f_ovr=False)
                                                              # routes DRDY + pressure-threshold events to INT pin
lps.configure_pressure_event(phe=True, ple=False, lir=False)  # Configure pressure event, (phe=False, ple=False, lir=False) → None
                                                              # arms high-event pressure interrupt
lps.autozero()                                             # Capture AUTOZERO reference, () → None
                                                              # current pressure becomes the zero reference
lps.reset_reference()                                      # Reset reference, () → None
                                                              # clears AUTOZERO/AUTOREFP and REF_P registers
ref = lps.reference_pressure()                             # Read reference pressure, () → float Pa
lps.set_fifo_mode(LPS22DFFull.FIFO_FIFO)                   # Set FIFO mode, (mode 0–5) → None
                                                              # selects FIFO mode; pass 0 first when switching
lps.set_fifo_watermark(64)                                 # Set FIFO watermark, (level 0–127) → None
                                                              # raises INT when 64 samples are buffered
count = lps.fifo_sample_count()                            # Read FIFO sample count, () → int
samples = lps.read_fifo()                                  # Read FIFO samples, () → list[float] Pa
                                                              # burst-reads all available pressure samples
src = lps.interrupt_source()                               # Read interrupt source, () → dict
                                                              # keys: boot_on, ia, ph, pl; clears register
print('T={} C, P={} Pa, alt={} m'.format(t, p, alt))
print('===DONE: 0 passed, 0 failed===')