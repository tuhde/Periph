use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp280Full, OSRS_X1, OSRS_X2, OSRS_X16, MODE_FORCED, MODE_NORMAL, FILTER_OFF, FILTER_16, T_SB_0_5_MS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp280Full::new(dev, addr).expect("init BMP280"); // Create BMP280 driver, (i2c, addr=0x76)

    // --- Weather monitoring preset: lowest power, forced mode ---
    // BMP280 datasheet Table 7: ×1/×1, filter off, forced mode.
    // One sample per second for 30 seconds.
    bmp.configure(OSRS_X1, OSRS_X1, MODE_FORCED, FILTER_OFF, T_SB_0_5_MS).expect("configure weather");  // Configure chip, (osrs_t=×1, osrs_p=×1, mode=forced, filter=off, t_sb=0) → ()

    let mut temps = Vec::new();
    let mut pressures = Vec::new();
    let mut alts = Vec::new();
    for n in 0..30 {
        let t = bmp.temperature().expect("read temperature");      // Read temperature, () → f32 °C
        let p = bmp.pressure().expect("read pressure");            // Read pressure, () → f32 hPa
        let a = bmp.altitude(1013.25).expect("compute altitude");  // Compute altitude, (sea_level_hpa=1013.25) → f32 m
        temps.push(t);
        pressures.push(p);
        alts.push(a);
        println!("{}s: {:.1} C, {:.1} hPa, alt={:.1} m", n, t, p, a);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }

    let avg_t: f32 = temps.iter().sum::<f32>() / temps.len() as f32;
    let avg_p: f32 = pressures.iter().sum::<f32>() / pressures.len() as f32;
    println!("Weather: T={:.1}/{:.1}/{:.1} C, P={:.1}/{:.1}/{:.1} hPa",
        temps.iter().cloned().fold(f32::INFINITY, f32::min), avg_t, temps.iter().cloned().fold(f32::NEG_INFINITY, f32::max),
        pressures.iter().cloned().fold(f32::INFINITY, f32::min), avg_p, pressures.iter().cloned().fold(f32::NEG_INFINITY, f32::max));

    // --- Indoor navigation preset: high resolution with IIR filter ---
    // ×16/×2, filter coefficient 16, normal mode at ~26 Hz.
    bmp.configure(OSRS_X2, OSRS_X16, MODE_NORMAL, FILTER_16, T_SB_0_5_MS).expect("configure indoor");  // Configure chip, (osrs_t=×2, osrs_p=×16, mode=normal, filter=16, t_sb=0.5ms) → ()

    let mut alts2 = Vec::new();
    for n in 0..30 {
        let t = bmp.temperature().expect("read temperature");      // Read temperature, () → f32 °C
        let p = bmp.pressure().expect("read pressure");            // Read pressure, () → f32 hPa
        let a = bmp.altitude(1013.25).expect("compute altitude");  // Compute altitude, (sea_level_hpa=1013.25) → f32 m
        alts2.push(a);
        println!("{}s: alt={:.4} m", n, a);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }

    let mean_alt: f32 = alts2.iter().sum::<f32>() / alts2.len() as f32;
    let variance: f32 = alts2.iter().map(|x| (x - mean_alt).powi(2)).sum::<f32>() / alts2.len() as f32;
    let std = variance.sqrt();
    println!("Navigation: alt min={:.4} max={:.4} std={:.4} m",
        alts2.iter().cloned().fold(f32::INFINITY, f32::min),
        alts2.iter().cloned().fold(f32::NEG_INFINITY, f32::max),
        std);
}
