use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{
    Lps28dfwFull, LPS28DFW_ODR_25_HZ, AVG_64, FS_MODE_1, LFPF_ODR_OVER_4,
    FIFO_FIFO, STATUS_P_DA,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut lps = Lps28dfwFull::new(dev, addr).expect("init LPS28DFW"); // Create LPS28DFW driver, (i2c, addr=0x5C)
    let cid = lps.chip_id().expect("read chip id");                       // Read chip ID, () → u8
    println!("chip_id=0x{:02x}", cid);                                    // returns 0xB4 for LPS28DFW
    lps.configure(LPS28DFW_ODR_25_HZ, AVG_64, FS_MODE_1, true, LFPF_ODR_OVER_4).expect("configure");  // Configure chip, (odr 0–8, avg 0–7, fs_mode 0/1, lpf_en bool, lpf_cfg 0/1) → ()
                                                                          // sets output data rate, averaging, full-scale, IIR filter
    lps.set_threshold(1050.0, true, true).expect("set threshold");         // Set pressure threshold, (threshold_hpa, high, low) → ()
                                                                          // arms PH/PL when pressure crosses threshold_hpa
    lps.set_offset(0.5).expect("set offset");                             // Set one-point calibration, (offset_hpa) → ()
                                                                          // subtracts 0.5 hPa from subsequent readings
    let ready = lps.is_data_ready().expect("is data ready");              // Check data ready, () → bool
                                                                          // reads STATUS.P_DA
    let (p, t) = lps.read().expect("read");                               // Read both values, () → (f32 hPa, f32 °C)
                                                                          // burst-reads pressure+temperature
    lps.softreset().expect("soft reset");                                  // Soft reset, () → ()
                                                                          // waits ~2 ms for reboot
    lps.fifo_configure(FIFO_FIFO, 16, true).expect("fifo configure");      // Configure FIFO, (mode 0–6, wtm 0–127, stop_on_wtm bool) → ()
                                                                          // enables 16-sample watermark FIFO
    let level = lps.fifo_level().expect("fifo level");                    // FIFO unread count, () → u8
    let mut samples = [0.0_f32; 128];
    lps.fifo_read(level, &mut samples).expect("fifo read");               // Drain FIFO, (count, buf) → ()
    let (os_p, os_t) = lps.read_oneshot().expect("oneshot");              // One-shot read, () → (f32 hPa, f32 °C)
                                                                          // triggers a single measurement with ODR=0
    let alt = lps.altitude(1013.25).expect("altitude");                   // Compute altitude, (sea_level_hpa=1013.25) → f32 m
    println!("chip=0x{:02x}, ready={}, p={:.2}, t={:.2}, alt={:.1}, level={}, os_p={:.2}, os_t={:.2}, samples={}",
             cid, ready, p, t, alt, level, os_p, os_t, level);
    let _ = STATUS_P_DA; // silence unused
}