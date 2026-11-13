use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::MCP3422Full;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    #[allow(non_snake_case)]
    let mut DELAY = Delay;
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x68);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut sensor = MCP3422Full::new(dev, addr);
    sensor.init().expect("init");
    let raw = sensor.read_raw(&mut DELAY, 0).expect("raw");
    check_true!(raw >= -32768 && raw <= 32767, "raw_in_16_bit_range", passed, failed);
    let v = sensor.read_voltage(&mut DELAY, 1).expect("voltage");
    check_true!(v >= -2.048 && v <= 2.048, "voltage_in_range", passed, failed);
    check_true!(sensor.read_all(&mut DELAY).is_ok(), "read_all_two_values", passed, failed);
    sensor.set_resolution(12).expect("resolution");
    sensor.set_gain(2).expect("gain");
    check_true!(sensor.get_resolution() == 12 && sensor.get_gain() == 2, "selection_roundtrip", passed, failed);
    check_true!((sensor.full_scale() - 1.024).abs() < 1e-6, "full_scale", passed, failed);
    let r12 = sensor.read_raw(&mut DELAY, 0).expect("raw12");
    check_true!(r12 >= -2048 && r12 <= 2047, "one_shot_12_bit_range", passed, failed);
    sensor.set_gain(1).expect("gain");
    sensor.start_continuous(Some(0)).expect("start");
    check_true!(sensor.is_continuous(), "continuous_flag", passed, failed);
    let rc = sensor.read_continuous_raw(&mut DELAY).expect("continuous");
    check_true!(rc >= -2048 && rc <= 2047, "continuous_sample_in_range", passed, failed);
    check_true!(sensor.read_config().expect("config") & 0x10 != 0, "read_config_continuous_bit", passed, failed);
    sensor.stop_continuous().expect("stop");
    check_true!(!sensor.is_continuous(), "stopped", passed, failed);
    let mut scan = [0.0f32; 2];
    check_true!(sensor.read_scan(&mut DELAY, &[0, 1], &mut scan).is_ok(), "read_scan_two_values", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
