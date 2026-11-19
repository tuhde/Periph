use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::Mpl115a2Full;

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
        .unwrap_or(0x60);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut baro = Mpl115a2Full::new(dev, addr).expect("init MPL115A2");

    let p = baro.read_pressure(&mut delay).unwrap();
    check_true!(p >= 50000.0 && p <= 115000.0, "pressure_range", passed, failed);
    let t = baro.read_temperature(&mut delay).unwrap();
    check_true!(t >= -40.0 && t <= 105.0, "temperature_range", passed, failed);
    let (padc, tadc) = baro.read_raw(&mut delay).unwrap();
    check_true!(padc <= 1023 && tadc <= 1023, "raw_range", passed, failed);
    let c = baro.compensate(padc, tadc);
    check_true!(c >= 50000.0 && c <= 115000.0, "compensate_range", passed, failed);
    let (pa, tc) = baro.read(&mut delay).unwrap();
    check_true!(pa >= 50000.0 && pa <= 115000.0 && tc >= -40.0 && tc <= 105.0, "read_both", passed, failed);
    let hpa = baro.pressure_hpa(&mut delay).unwrap();
    check_true!(hpa >= 500.0 && hpa <= 1150.0, "pressure_hpa", passed, failed);
    let alt = baro.altitude(101325.0, &mut delay).unwrap();
    check_true!(alt >= -1000.0 && alt <= 6000.0, "altitude", passed, failed);
    check_true!(baro.reload_coefficients().is_ok() && baro.coefficients().0 != 0.0, "coefficients", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
