use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Lps28dfwFull, ODR_25_HZ, AVG_64, FS_MODE_1, LFPF_ODR_OVER_4};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5C);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");

    // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
    // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
    let mut lps = Lps28dfwFull::new(dev, addr).expect("init LPS28DFW"); // Create LPS28DFW driver, (i2c, addr=0x5C)
    lps.configure(ODR_25_HZ, AVG_64, FS_MODE_1, true, LFPF_ODR_OVER_4).expect("configure");  // Configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpf_en=true, lpf_cfg=ODR/4) → ()

    // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
    // Sea-level reference uses the ISA standard (1013.25 hPa).
    let mut samples: u32 = 0;
    for n in 0..60 {
        let (p, t) = lps.read().expect("read");                       // Read both values, () → (f32 hPa, f32 °C)
        let alt = 44330.0 * (1.0 - libm::powf(p / 1013.25, 1.0 / 5.255));
        let elapsed = (n + 1) as f32 * 0.5;
        println!("{:.1}s  {:.2} hPa  {:.2} C  {:.1} m", elapsed, p, t, alt);
        samples += 1;
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
    println!("Total samples: {}", samples);
}