from periph.connection.i2c_auto import I2CConnection
from periph.chips.gyroscope.l3g4200d import L3G4200DFull

connection = I2CConnection(0x68)
gyro = L3G4200DFull(connection)                            # Create L3G4200D driver, (connection, bus_type='i2c')
cid = gyro.who_am_i()                                      # Read WHO_AM_I, () → int
                                                            # returns 0xD3 for L3G4200D
gyro.configure(odr=200, bandwidth=0, full_scale=500)       # Configure chip, (odr 100/200/400/800 Hz, bandwidth 0–3, full_scale 250/500/2000 dps) → None
                                                            # sets CTRL_REG1 DR/BW and CTRL_REG4 FS
gyro.enable_axes(x=True, y=True, z=True)                   # Enable axes, (x=True, y=True, z=True) → None
                                                            # sets Xen/Yen/Zen bits in CTRL_REG1
gyro.set_full_scale(2000)                                  # Set full scale, (full_scale 250/500/2000 dps) → None
                                                            # updates FS[1:0] in CTRL_REG4
ready = gyro.data_ready()                                  # Check data ready, () → bool
                                                            # returns STATUS_REG.ZYXDA
status = gyro.status()                                     # Read STATUS, () → int
                                                            # raw status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA)
temp = gyro.temperature()                                  # Read temperature, () → int
                                                            # 8-bit signed relative count (−1 °C/digit)
gyro.enable_highpass(mode=0, cutoff=4)                     # Enable high-pass, (mode 0–3, cutoff 0–9) → None
                                                            # sets HPen and HPM/HPCF; cutoff depends on ODR
gyro.disable_highpass()                                    # Disable high-pass, () → None
                                                            # clears HPen in CTRL_REG5
gyro.set_interrupt(x_high=True, y_high=True, z_high=True)  # Configure INT1, (x_high, x_low, y_high, y_low, z_high, z_low, and_mode, latch) → None
                                                            # enable high events on all axes; INT1 asserts on any
gyro.set_threshold('x', 90.0)                              # Set X threshold, (axis 'x'/'y'/'z', threshold_dps) → None
                                                            # converts dps to raw 15-bit value via sensitivity
gyro.set_duration(samples=4, wait=False)                   # Set INT1 duration, (samples 0–127, wait=False) → None
                                                            # INT1 must be true for `samples` ODR cycles before firing
gyro.set_data_ready_pin(True)                              # Route DRDY to INT2, (enable=True) → None
                                                            # sets I2_DRDY in CTRL_REG3
gyro.enable_fifo(mode=2, watermark=10)                     # Enable FIFO, (mode 0–4, watermark=0) → None
                                                            # mode 2 = stream mode; watermark=10 frames
gyro.disable_fifo()                                        # Disable FIFO, () → None
                                                            # bypass mode and clear FIFO_EN
samples = gyro.fifo_samples()                              # Read FIFO count, () → int
                                                            # FSS[4:0] from FIFO_SRC_REG
gyro.power_down()                                          # Enter power-down, () → None
                                                            # clears PD in CTRL_REG1
gyro.wake_up()                                             # Wake from power-down, () → None
                                                            # sets PD; previously enabled axes restored
gyro.sleep()                                               # Enter sleep mode, () → None
                                                            # PD=1, all axes off
gyro.read_int_source()                                     # Read & clear INT1_SRC, () → int
                                                            # reading clears the interrupt-active bit
x, y, z = gyro.angular_rate()                              # Read X/Y/Z angular rate, () → (float, float, float) rad/s
print('X={:.2f} Y={:.2f} Z={:.2f} rad/s, T={}, ready={}, status=0x{:02X}, fifo={}, cid=0x{:02X}'.format(
    x, y, z, temp, ready, status, samples, cid))
print('===DONE: 0 passed, 0 failed===')
