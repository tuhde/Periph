use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp280Full, OSRS_X1, OSRS_X16, OSRS_X2, MODE_FORCED, MODE_NORMAL, FILTER_OFF, FILTER_16, T_SB_0_5_MS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok().and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok()).unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp280Full::new(dev, addr, 1, 1, 1, 0, 0).expect("init BMP280");  // Create BMP280 driver, (transport, addr=0x76)

    // --- Weather monitoring preset: forced mode, ×1/×1, filter off ---
    bmp.configure(OSRS_X1, OSRS_X1, MODE_FORCED, FILTER_OFF, T_SB_0_5_MS).expect("configure");  // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → ()
    println!("WEATHER-MONITORING  T[C]   P[hPa]   ALT[m]");

    for n in 0..30 {
        let t = bmp.temperature().expect("read temperature");             // Read temperature, () → f32 C
        let p = bmp.pressure().expect("read pressure");                   // Read pressure, () → f32 hPa
        let a = bmp.altitude(1013.25).expect("compute altitude");         // Compute altitude, (sea_level_hpa=1013.25) → f32 m
        println!("{:4d}s: {:.2f} C   {:.2f} hPa   {:.2f} m", n, t, p, a);
        std::thread::sleep(std::time::Duration::from_secs(1));
    }

    // --- Switch to indoor-navigation preset: normal mode, ×16/×2, filter 16 ---
    bmp.configure(OSRS_X16, OSRS_X2, MODE_NORMAL, FILTER_16, T_SB_0_5_MS).expect("configure");  // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → ()
    println!("\nINDOOR-NAVIGATION (normal mode, filter=16)");
    println!("      n   ALT[m]    delta[cm]");

    let mut prev_alt = 0.0f32;
    for n in 0..30 {
        let t = bmp.temperature().expect("read temperature");             // Read temperature, () → f32 C
        let p = bmp.pressure().expect("read pressure");                   // Read pressure, () → f32 hPa
        let a = bmp.altitude(1013.25).expect("compute altitude");         // Compute altitude, (sea_level_hpa=1013.25) → f32 m
        let da = (a - prev_alt) * 100.0;
        if n > 0 {
            println!("{:4d}:  {:.4f}   {:+.1f} cm", n, a, da);
        } else {
            println!("{:4d}:  {:.4f}", n, a);
        }
        prev_alt = a;
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}