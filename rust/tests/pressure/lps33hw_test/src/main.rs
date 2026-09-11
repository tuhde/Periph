use linux_embedded_hal::I2cdev;
use periph::chips::pressure::{Lps33hwMinimal, Lps33hwFull, ODR_10_HZ};

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
        .unwrap_or(0x5C);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lps33hwMinimal::new(dev, addr).expect("init LPS33HW");

    let t = chip.temperature().unwrap();
    check_true!(t >= -40.0 && t <= 85.0, "temperature_range", passed, failed);

    let p = chip.pressure().unwrap();
    check_true!(p >= 26000.0 && p <= 126000.0, "pressure_range", passed, failed);

    drop(chip);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip_full = Lps33hwFull::new(dev, addr).expect("init LPS33HW Full");

    chip_full.configure(ODR_10_HZ, true, true, 1, false, false).unwrap();
    check_true!(true, "configure", passed, failed);

    let (p_os, t_os) = chip_full.one_shot().unwrap_or((-1.0, -300.0));
    check_true!(p_os >= 26000.0 && p_os <= 126000.0, "one_shot_pressure", passed, failed);
    check_true!(t_os >= -40.0 && t_os <= 85.0, "one_shot_temperature", passed, failed);

    let st = chip_full.status().unwrap();
    check_true!(true, "status", passed, failed);

    let intsrc = chip_full.interrupt_status().unwrap();
    check_true!(true, "interrupt_status", passed, failed);

    chip_full.reset().unwrap();
    check_true!(true, "reset", passed, failed);

    chip_full.reboot().unwrap();
    check_true!(true, "reboot", passed, failed);

    chip_full.set_pressure_offset(0.0).unwrap();
    check_true!(true, "set_pressure_offset", passed, failed);

    chip_full.enable_fifo(1, 16).unwrap();
    let fst = chip_full.fifo_status().unwrap();
    check_true!(true, "fifo_status", passed, failed);

    chip_full.disable_fifo().unwrap();
    check_true!(true, "disable_fifo", passed, failed);

    chip_full.reset_lpf().unwrap();
    check_true!(true, "reset_lpf", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}