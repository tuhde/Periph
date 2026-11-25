use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HTU31DFull;

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
        .unwrap_or(0x40);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = HTU31DFull::new(dev, addr);

    chip.init(&mut delay).expect("init");
    let (t, rh) = chip.read(&mut delay).expect("read");
    check_true!(t > -40.0 && t < 125.0, "read_temperature_range", passed, failed);
    check_true!(rh >= 0.0 && rh <= 100.0, "read_humidity_range", passed, failed);
    let h = chip.read_humidity(&mut delay).expect("rh");
    check_true!(h >= 0.0 && h <= 100.0, "read_humidity_single", passed, failed);
    let tc = chip.read_temperature(&mut delay).expect("t");
    check_true!(tc > -40.0 && tc < 125.0, "read_temperature_single", passed, failed);
    let (tr, rr) = chip.read_raw(&mut delay).expect("raw");
    check_true!(tr as u32 <= 65535 && rr as u32 <= 65535, "read_raw_range", passed, failed);
    check_true!(chip.dew_point(&mut delay).expect("dew") <= tc + 1.0, "dew_point_below_temperature", passed, failed);
    check_true!(chip.serial_number().expect("serial") <= 0xFFFFFF, "serial_number_24bit", passed, failed);
    check_true!(chip.diagnostic().expect("diagnostic") & periph::chips::humidity::htu31d::DIAG_NVM_ERR == 0, "diagnostic_no_nvm_error", passed, failed);
    for osr in 0..4u8 {
        chip.set_resolution(osr, osr).expect("resolution");
        check_true!(chip.get_resolution() == (osr, osr), "resolution_roundtrip", passed, failed);
        let (_, r) = chip.read(&mut delay).expect("read");
        check_true!(r >= 0.0 && r <= 100.0, "read_at_osr", passed, failed);
    }
    chip.set_resolution(3, 3).expect("resolution");
    check_true!((chip.conversion_time() - 0.02032).abs() < 1e-5, "conversion_time_osr33", passed, failed);
    chip.start_conversion().expect("start");
    sleep_ms!(30);
    let (_, r) = chip.read_result().expect("result");
    check_true!(r >= 0.0 && r <= 100.0, "start_conversion_read_result", passed, failed);
    chip.set_heater(true).expect("heater on");
    check_true!(chip.is_heater_on().expect("is_heater_on"), "heater_on", passed, failed);
    chip.set_heater(false).expect("heater off");
    check_true!(!chip.is_heater_on().expect("is_heater_on"), "heater_off", passed, failed);
    chip.software_reset(&mut delay).expect("reset");
    let (_, r) = chip.read(&mut delay).expect("read");
    check_true!(r >= 0.0 && r <= 100.0, "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
