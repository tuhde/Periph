use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Bmp180Minimal, Bmp180Full, OSS_ULP};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x77);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp = Bmp180Minimal::new(dev, addr).expect("init BMP180");

    bmp.inner.b5 = 0;
    bmp.inner.ac1 = 408;
    bmp.inner.ac2 = -72;
    bmp.inner.ac3 = -14383;
    bmp.inner.ac4 = 32741;
    bmp.inner.ac5 = 32757;
    bmp.inner.ac6 = 23153;
    bmp.inner.b1 = 6190;
    bmp.inner.b2 = 4;
    bmp.inner.mc = -8711;
    bmp.inner.md = 2868;

    let b5 = periph::chips::pressure::bmp180::compensate_temp(
        27898, bmp.inner.ac1, bmp.inner.ac2, bmp.inner.ac3,
        bmp.inner.ac5, bmp.inner.ac6, bmp.inner.mc, bmp.inner.md
    );
    check_true!(b5 != 0, "temp_compensation_b5", passed, failed);

    drop(bmp);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut bmp_full = Bmp180Full::new(dev, addr, OSS_ULP).expect("init BMP180 Full");
    check_true!(bmp_full.oversampling() == 0, "default_oss", passed, failed);
    bmp_full.set_oversampling(2);
    check_true!(bmp_full.oversampling() == 2, "set_oss", passed, failed);

    let alt = bmp_full.altitude(1013.25).unwrap_or(-1.0);
    check_true!(alt >= 0.0, "altitude", passed, failed);

    let slp = bmp_full.sea_level_pressure(0.0).unwrap_or(0.0);
    check_true!(slp >= 900.0 && slp <= 1100.0, "sea_level_pressure", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
