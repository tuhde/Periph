use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp280Minimal, Bmp280Full, OSRS_X2, OSRS_X4, MODE_FORCED, FILTER_4, T_SB_62_5_MS};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok().and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok()).unwrap_or(0x76);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp280Minimal::new(dev, addr).expect("init BMP280");

    let t = bmp.temperature().expect("temperature");
    assert!(t >= -40.0 && t <= 85.0, "temperature out of range: {}", t);
    println!("PASS temperature_range");

    let p = bmp.pressure().expect("pressure");
    assert!(p >= 300.0 && p <= 1100.0, "pressure out of range: {}", p);
    println!("PASS pressure_range");

    let dev2 = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp_full = Bmp280Full::new(dev2, addr, 1, 1, 1, 0, 0).expect("init BMP280 full");

    let cid = bmp_full.chip_id().expect("chip_id");
    assert_eq!(cid, 0x58, "wrong chip_id: {:02x}", cid);
    println!("PASS chip_id");

    bmp_full.configure(OSRS_X2, OSRS_X4, MODE_FORCED, FILTER_4, T_SB_62_5_MS).expect("configure");
    println!("PASS configure");

    bmp_full.inner.dig_T1 = 27504;
    bmp_full.inner.dig_T2 = 26435;
    bmp_full.inner.dig_T3 = -1000;
    bmp_full.inner.dig_P1 = 36477;
    bmp_full.inner.dig_P2 = -10685;
    bmp_full.inner.dig_P3 = 3024;
    bmp_full.inner.dig_P4 = 2855;
    bmp_full.inner.dig_P5 = 140;
    bmp_full.inner.dig_P6 = -7;
    bmp_full.inner.dig_P7 = 15500;
    bmp_full.inner.dig_P8 = -14600;
    bmp_full.inner.dig_P9 = 6000;
    bmp_full.inner.t_fine = 0;

    let t_val = bmp_full.inner.compensate_temp(519888);
    assert!((t_val - 25.08).abs() < 0.1, "compensate_temp failed: {} != 25.08", t_val);
    println!("PASS compensate_temp");

    let p_val = bmp_full.inner.compensate_pressure(415148);
    assert!((p_val - 1006.53).abs() < 0.5, "compensate_pressure failed: {} != 1006.53", p_val);
    println!("PASS compensate_pressure");

    bmp_full.reset().expect("reset");
    println!("PASS reset");

    println!("===DONE: 8 passed, 0 failed===");
}