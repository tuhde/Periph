use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp085Full, OSS_ULP};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp085Full::new(dev, 0x77, OSS_ULP).expect("init BMP085"); // Create BMP085 driver, (connection, oss=0 ULP)

    let t0 = bmp.temperature().expect("read temperature");                   // Read temperature, () → f32 C
    let p0 = bmp.pressure().expect("read pressure");                        // Read pressure, () → f32 Pa
    let alt_ref = bmp.altitude(101325.0).expect("compute altitude");       // Compute altitude, (sea_level_pa=101325.0) → f32 m
    println!("Reference: {:.1} C, {:.1} Pa, alt={:.1} m", t0, p0, alt_ref);
    let mut prev_alt = 0.0f32;

    for n in 0..60 {
        let t = bmp.temperature().expect("read temperature");               // Read temperature, () → f32 C
        let p = bmp.pressure().expect("read pressure");                     // Read pressure, () → f32 Pa
        let a = bmp.altitude(101325.0).expect("compute altitude");         // Compute altitude, (sea_level_pa=101325.0) → f32 m
        let da = (a - prev_alt) * 100.0;

        if n > 0 {
            println!("{}s: {:.1} C, {:.1} Pa, alt={:.1} m (delta={:+.0} cm)", n, t, p, a, da);
        } else {
            println!("{}s: {:.1} C, {:.1} Pa, alt={:.1} m", n, t, p, a);
        }
        prev_alt = a;
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}