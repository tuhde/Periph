use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp180Full, OSS_STANDARD};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp180Full::new(dev, 0x77, 0).expect("init BMP180"); // Create BMP180 driver, (transport, oss=0)
    let cid = bmp.chip_id().expect("read chip id");                    // Read chip ID, () → u8
    println!("chip_id=0x{:02x}", cid);                                  // returns 0x55 for BMP180
    let oss = bmp.oversampling();                                      // Read OSS, () → u8 0–3
    println!("oss={}", oss);
    bmp.set_oversampling(OSS_STANDARD);                                // Set OSS, (oss 0–3) → ()
                                                                     // changes conversion time vs resolution trade-off
    let t = bmp.temperature().expect("read temperature");             // Read temperature, () → f32 C
    let p = bmp.pressure().expect("read pressure");                   // Read pressure, () → f32 hPa
    let alt = bmp.altitude(1013.25).expect("compute altitude");       // Compute altitude, (sea_level_hpa=1013.25) → f32 m
                                                                     // uses barometric formula to convert pressure to metres
    let slp = bmp.sea_level_pressure(alt).expect("compute slp");       // Compute sea-level pressure, (altitude_m) → f32 hPa
    bmp.reset().expect("soft reset");                                  // Soft reset chip, () → ()
                                                                     // re-reads calibration after reset
    println!("T={:.1} C, P={:.1} hPa, alt={:.1} m, slp={:.1} hPa", t, p, alt, slp);
}
