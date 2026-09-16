use linux_embedded_hal::I2cdev;
use periph::chips::gyroscope::{
    L3g4200dFull, FS_250_DPS, FS_500_DPS, ODR_200_HZ, FIFO_STREAM,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut gyro = L3g4200dFull::new(dev, addr, false).expect("init L3G4200D");      // Create L3G4200D driver, (i2c, addr=0x68, spi=false)
    let cid = gyro.who_am_i().expect("who_am_i");                                     // Read WHO_AM_I, () → u8
                                                                                       // returns 0xD3 for L3G4200D
    gyro.configure(ODR_200_HZ, 0, FS_500_DPS).expect("configure");                     // Configure chip, (odr ODR_200_HZ, bandwidth 0–3, full_scale 250/500/2000) → ()
                                                                                       // sets CTRL_REG1 DR/BW and CTRL_REG4 FS
    gyro.enable_axes(true, true, true).expect("enable_axes");                          // Enable axes, (x, y, z) → ()
                                                                                       // sets Xen/Yen/Zen bits in CTRL_REG1
    gyro.set_full_scale(2000).expect("set_full_scale");                                // Set full scale, (full_scale 250/500/2000) → ()
                                                                                       // updates FS[1:0] in CTRL_REG4
    let ready = gyro.data_ready().expect("data_ready");                                // Check data ready, () → bool
                                                                                       // returns STATUS_REG.ZYXDA
    let status = gyro.status().expect("status");                                       // Read STATUS, () → u8
                                                                                       // raw status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA)
    let temp = gyro.temperature().expect("temperature");                                // Read temperature, () → i8
                                                                                       // 8-bit signed relative count (−1 °C/digit)
    gyro.enable_highpass(0, 4).expect("enable_highpass");                             // Enable high-pass, (mode 0–3, cutoff 0–9) → ()
                                                                                       // sets HPen and HPM/HPCF; cutoff depends on ODR
    gyro.disable_highpass().expect("disable_highpass");                                // Disable high-pass, () → ()
                                                                                       // clears HPen in CTRL_REG5
    gyro.set_interrupt(true, false, true, false, true, false, false, true).expect("set_interrupt");  // Configure INT1, (x_high, x_low, y_high, y_low, z_high, z_low, and_mode, latch) → ()
                                                                                       // enable high events on x/y/z; latch until INT1_SRC read
    gyro.set_threshold('x', 87.5).expect("set_threshold");                            // Set X threshold, (axis 'x'/'y'/'z', threshold_dps) → ()
                                                                                       // converts dps to raw 15-bit value via sensitivity
    gyro.set_duration(4, false).expect("set_duration");                                // Set INT1 duration, (samples 0–127, wait=false) → ()
                                                                                       // INT1 must be true for `samples` ODR cycles before firing
    gyro.set_data_ready_pin(true).expect("set_data_ready_pin");                        // Route DRDY to INT2, (enable=true) → ()
                                                                                       // sets I2_DRDY in CTRL_REG3
    gyro.enable_fifo(FIFO_STREAM, 10).expect("enable_fifo");                          // Enable FIFO, (mode 0–4, watermark=0) → ()
                                                                                       // mode FIFO_STREAM = stream; watermark=10
    gyro.disable_fifo().expect("disable_fifo");                                        // Disable FIFO, () → ()
                                                                                       // bypass mode and clear FIFO_EN
    let samples = gyro.fifo_samples().expect("fifo_samples");                          // Read FIFO count, () → u8
                                                                                       // FSS[4:0] from FIFO_SRC_REG
    gyro.power_down().expect("power_down");                                            // Enter power-down, () → ()
                                                                                       // clears PD in CTRL_REG1
    gyro.wake_up().expect("wake_up");                                                  // Wake from power-down, () → ()
                                                                                       // sets PD; previously enabled axes restored
    gyro.sleep().expect("sleep");                                                      // Enter sleep mode, () → ()
                                                                                       // PD=1, all axes off
    let int_src = gyro.read_int_source().expect("read_int_source");                    // Read & clear INT1_SRC, () → u8
                                                                                       // reading clears the interrupt-active bit
    let (x, y, z) = gyro.angular_rate().expect("angular_rate");                        // Read X/Y/Z angular rate, () → (f32, f32, f32) rad/s
    println!("X={:.2} Y={:.2} Z={:.2} rad/s, T={}, ready={}, status=0x{:02X}, fifo={}, src=0x{:02X}, cid=0x{:02X}",
             x, y, z, temp, ready, status, samples, int_src, cid);
}
