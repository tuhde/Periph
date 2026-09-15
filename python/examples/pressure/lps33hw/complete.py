from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.lps33hw import LPS33HWFull

connection = I2CConnection(0x5C)
lps = LPS33HWFull(connection)                          # Create LPS33HW driver, (connection)

lps.configure(odr=1, bdu=True, en_lpfp=True, lpfp_cfg=1, lc_en=False, sim=False)  # Configure chip, (odr 0–5, bdu bool, en_lpfp bool, lpfp_cfg 0/1, lc_en bool, sim bool) → None
                                                             # sets ODR=1Hz, BDU=1, ODR/20 LPF bandwidth
lps.set_pressure_offset(0.0)                           # Set pressure offset, (offset_hPa float) → None
                                                             # writes RPDS_L and RPDS_H from hPa/16 units
lps.one_shot()                                         # Trigger one-shot measurement, () → (pressure_Pa, temperature_C) tuple
                                                             # requires ODR=0; polls P_DA+T_DA until ready
st = lps.status()                                      # Read status register, () → dict
                                                             # keys: p_da, t_da, p_or, t_or
ist = lps.interrupt_status()                           # Read INT_SOURCE, () → dict
                                                             # keys: ia, p_high, p_low, boot_status
lps.configure_pressure_interrupt(high_en=True, low_en=True, threshold_hPa=5.0, latch=True)  # Configure pressure interrupt, (high_en bool, low_en bool, threshold_hPa float, latch bool) → None
                                                             # arms the ALERT pin when pressure crosses ±5 hPa
lps.configure_interrupt(drdy=True, f_fth=False, f_ovr=False, f_fss5=False, int_s=3, active_low=True, open_drain=True)  # Configure INT_DRDY pin, (drdy, f_fth, f_ovr, f_fss5 bools, int_s 0–3, active_low bool, open_drain bool) → None
                                                             # routes data-ready and pressure events to the INT_DRDY pin
lps.enable_fifo(mode=1, watermark=15)                  # Enable FIFO, (mode 0–7 not 5, watermark 0–31) → None
                                                             # stream-until-watermark-then-stop
fs = lps.fifo_status()                                 # Read FIFO_STATUS, () → dict
                                                             # keys: fth, ovr, count
samples = lps.read_fifo()                              # Drain FIFO, () → list of (pressure_Pa, temperature_C) tuples
lps.disable_fifo()                                     # Disable FIFO, () → None
lps.reset_lpf()                                        # Read LPFP_RES, () → None
                                                             # flushes transitory LPF state
lps.set_autozero()                                     # Set AUTOZERO, () → None
                                                             # stores current pressure in REF_P
lps.clear_autozero()                                   # Clear AUTOZERO, () → None
lps.set_autorifp()                                     # Set AUTORIFP, () → None
                                                             # stores next measurement in RPDS
lps.clear_autorifp()                                   # Clear AUTORIFP, () → None
lps.reboot()                                           # Reboot factory trimming, () → None
lps.reset()                                            # Software reset, () → None

print('T={} C, P={} Pa, status={}'.format(samples[-1][1] if samples else 0,
                                            samples[-1][0] if samples else 0, st))
print('===DONE: 0 passed, 0 failed===')