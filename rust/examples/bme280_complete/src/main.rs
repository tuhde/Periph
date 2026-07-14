use linux_embedded_hal::I2cdev;
use periph::chips::environmental::{
    Bme280Full, OSRS_X1, OSRS_X2, OSRS_X4,
    MODE_FORCED, FILTER_4, T_SB_125_MS,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bme = Bme280Full::new(dev, addr, false).expect("init BME280"); // Create BME280 driver, (i2c, addr=0x76, spi=false)
    let cid = bme.chip_id().expect("read chip id");                 // Read chip ID, () → u8
    println!("chip_id=0x{:02x}", cid);                              // returns 0x60 for BME280
    bme.configure(OSRS_X1, OSRS_X1, OSRS_X1, MODE_FORCED, FILTER_4, T_SB_125_MS).expect("configure");  // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → ()
                                                                         // writes ctrl_hum, config, ctrl_meas in correct order
    bme.set_oversampling(OSRS_X4, OSRS_X2, OSRS_X1).expect("set osrs");  // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → ()
                                                                         // humidity update requires ctrl_meas write to latch
    bme.set_mode(MODE_FORCED).expect("set mode");                  // Set power mode, (mode 0/1/3) → ()
    bme.set_filter(FILTER_4).expect("set filter");                 // Set IIR filter, (coeff 0–4) → ()
                                                                         // suppresses short-term pressure disturbances
    bme.set_standby(T_SB_125_MS).expect("set standby");            // Set standby time, (t_sb 0–7) → ()
                                                                         // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
    let _st = bme.status().expect("read status");                  // Read status register, () → u8
    let t = bme.temperature().expect("read temperature");          // Read temperature, () → f32 °C
    let p = bme.pressure().expect("read pressure");                // Read pressure, () → f32 hPa
    let h = bme.humidity().expect("read humidity");                // Read humidity, () → f32 %RH
    let alt = bme.altitude(1013.25).expect("compute altitude");    // Compute altitude, (sea_level_hpa=1013.25) → f32 m
                                                                         // uses barometric formula to convert pressure to metres
    let slp = bme.sea_level_pressure(alt).expect("compute slp");   // Compute sea-level pressure, (altitude_m) → f32 hPa
    let dp = bme.dew_point().expect("compute dew point");          // Compute dew point, () → f32 °C
                                                                         // Magnus-Tetens approximation from current T and RH
    bme.reset().expect("soft reset");                              // Soft reset chip, () → ()
                                                                         // re-reads calibration and re-applies configuration
    println!("T={:.1} C, P={:.1} hPa, RH={:.1} %RH, alt={:.1} m, slp={:.1} hPa, dp={:.1} C", t, p, h, alt, slp, dp);
}
