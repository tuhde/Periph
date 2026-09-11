use std::time::{Duration, Instant};

use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp384Full, MODE_NORMAL};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp384Full::new(dev, addr, false).expect("init BMP384"); // Create BMP384 driver, (i2c, addr=0x76)

    // --- Configure for noise-sensitive altitude logging ---
    // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
    // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
    // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
    bmp.configure(4, 1, 2, 0x03).expect("configure");             // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → Result<()>
    bmp.set_mode(MODE_NORMAL).expect("set_mode");                  // Set power mode, (mode) → Result<()>

    // --- Sample for 30 seconds, logging altitude every 500 ms ---
    // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
    const SEA_LEVEL_HPA: f32 = 1013.25;
    let start = Instant::now();
    let mut next = start;
    let mut rows: u32 = 0;
    while start.elapsed() < Duration::from_secs(30) {
        let now = Instant::now();
        if now >= next {
            let t = bmp.temperature().expect("temperature");      // Read temperature, () → f32 °C
            let p = bmp.pressure().expect("pressure");            // Read pressure, () → f32 hPa
            let altitude = 44330.0 * (1.0 - (p / SEA_LEVEL_HPA).powf(1.0 / 5.255));
            let elapsed = now.duration_since(start).as_secs_f32();
            println!("{:.1}s  {:.2} hPa  {:.1} C  {:.1} m", elapsed, p, t, altitude);
            rows += 1;
            next += Duration::from_millis(500);
        }
        std::thread::sleep(Duration::from_millis(50));
    }

    println!("Sampled {} rows over 30 s", rows);
}
