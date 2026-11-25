use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{BH1750Full, BH1750Mode as Mode};

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
        .unwrap_or(0x23);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = BH1750Full::new(dev, addr);

    chip.init().expect("init");
    check_true!(chip.read(&mut delay).map(|l| l >= 0.0 && l <= 120000.0).expect("read"), "read_range", passed, failed);
    check_true!(chip.read_raw(&mut delay).expect("raw") as u32 <= 65535, "read_raw_range", passed, failed);
    for (name, mode) in [("h", Mode::H), ("h2", Mode::H2), ("l", Mode::L)] {
        let l = chip.measure_once(mode, &mut delay).expect("measure");
        check_true!(l >= 0.0 && l <= 120000.0, name, passed, failed);
    }
    check_true!((chip.lux_per_count(Mode::H) - 1.0 / 1.2).abs() < 1e-5, "lux_per_count_default", passed, failed);
    check_true!((chip.measurement_time(Mode::H) - 0.180).abs() < 1e-5, "measurement_time_default", passed, failed);
    chip.start_continuous(Mode::H).expect("continuous");
    sleep_ms!(200);
    let l = chip.read_continuous().expect("continuous");
    check_true!(l >= 0.0 && l <= 120000.0, "read_continuous_range", passed, failed);
    chip.set_measurement_time(138).expect("mtreg");
    check_true!(chip.get_measurement_time_register() == 138, "mtreg_roundtrip", passed, failed);
    check_true!((chip.measurement_time(Mode::H) - 0.360).abs() < 1e-5, "measurement_time_scaled", passed, failed);
    let l = chip.measure_once(Mode::H, &mut delay).expect("measure");
    check_true!(l >= 0.0 && l <= 120000.0, "read_with_mtreg", passed, failed);
    chip.set_sensitivity(1.0).expect("sensitivity");
    check_true!(chip.get_measurement_time_register() == 69, "sensitivity_default", passed, failed);
    chip.power_down().expect("power down");
    chip.power_on().expect("power on");
    chip.reset().expect("reset");
    let l = chip.measure_once(Mode::H, &mut delay).expect("measure");
    check_true!(l >= 0.0 && l <= 120000.0, "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
