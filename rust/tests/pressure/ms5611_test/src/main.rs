use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::{MS5611Conversion as Conversion, MS5611Full};

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

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = MS5611Full::new(dev, addr);

    chip.init(&mut delay).expect("init");
    check_true!(chip.prom_crc_ok(), "prom_crc_ok", passed, failed);
    let (t, p) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 85.0, "read_temperature_range", passed, failed);
    check_true!(p > 30000.0 && p < 120000.0, "read_pressure_range", passed, failed);
    let ps = chip.read_pressure(&mut delay).expect("p");
    check_true!(ps > 30000.0 && ps < 120000.0, "read_pressure_single", passed, failed);
    let tc = chip.read_temperature(&mut delay).expect("t");
    check_true!(tc > -40.0 && tc < 85.0, "read_temperature_single", passed, failed);
    let (d1, d2) = chip.read_raw(&mut delay).expect("raw");
    check_true!(d1 > 0 && d2 > 0, "read_raw", passed, failed);
    let (_, pc) = chip.compensate(d1, d2, true);
    check_true!((pc - p).abs() < 200.0, "compensate_matches_read", passed, failed);
    check_true!(chip.coefficients().iter().all(|&c| c > 0), "coefficients_nonzero", passed, failed);
    check_true!(chip.serial_code() < 4096, "serial_code_range", passed, failed);
    check_true!(chip.read_prom().is_ok(), "read_prom", passed, failed);
    for osr in [256u16, 512, 1024, 2048, 4096] {
        chip.set_oversampling(osr).expect("osr");
        check_true!(chip.get_oversampling() == osr, "osr_roundtrip", passed, failed);
        let v = chip.read_pressure(&mut delay).expect("p");
        check_true!(v > 30000.0 && v < 120000.0, "osr_read", passed, failed);
    }
    chip.start_conversion(Conversion::Temperature, None).expect("start");
    sleep_ms!(12);
    check_true!(chip.read_adc().map(|v| v > 0).unwrap_or(false), "non_blocking_adc", passed, failed);
    let alt = chip.altitude(&mut delay, 101325.0).expect("alt");
    check_true!(alt > -500.0 && alt < 6000.0, "altitude_plausible", passed, failed);
    chip.reset(&mut delay).expect("reset");
    let v = chip.read_pressure(&mut delay).expect("p");
    check_true!(v > 30000.0 && v < 120000.0, "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
