use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::color::{Isl29125Channel, Isl29125Full, Isl29125Mode, ISL29125_I2C_ADDRESS, ISL29125_SOURCE_THRESHOLD, ISL29125_STATUS_THRESHOLD};

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
        .unwrap_or(ISL29125_I2C_ADDRESS);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Isl29125Full::new(dev, addr);
    chip.init().expect("init");

    check_true!(chip.device_id().ok() == Some(0x7D), "device_id", passed, failed);
    let c = chip.read(&mut delay).expect("read");
    check_true!((c.red as u32 + c.green as u32 + c.blue as u32) < 3 * 65536, "read_range", passed, failed);
    check_true!(chip.set_range(375).is_ok() && chip.set_range(10_000).is_ok(), "set_range", passed, failed);
    check_true!(chip.set_resolution(12).is_ok() && chip.set_resolution(16).is_ok(), "set_resolution", passed, failed);
    check_true!(chip.set_range(100).is_err() && chip.set_resolution(8).is_err(), "invalid_config", passed, failed);
    check_true!(chip.set_ir_compensation(true, 0x3F).is_ok(), "ir_compensation", passed, failed);
    let _ = chip.set_sync(false);
    check_true!(chip.read_rgb().is_ok(), "read_rgb", passed, failed);
    check_true!(chip.read_green().is_ok() && chip.read_red().is_ok() && chip.read_blue().is_ok(), "read_single", passed, failed);
    check_true!(chip.set_mode(Isl29125Mode::Green).is_ok() && chip.read_green().is_ok(), "green_mode_reads", passed, failed);
    let _ = chip.set_mode(Isl29125Mode::Rgb);
    check_true!(chip.set_thresholds(100, 60_000).is_ok() && chip.get_thresholds().ok() == Some((100, 60_000)), "thresholds_roundtrip", passed, failed);
    check_true!(chip.set_persistence(2).is_ok() && chip.set_interrupt_channel(Isl29125Channel::Green).is_ok(), "interrupt_config", passed, failed);
    let _ = chip.enable_interrupt(ISL29125_SOURCE_THRESHOLD);
    let _ = chip.disable_interrupt(ISL29125_SOURCE_THRESHOLD);
    let _ = chip.status();
    check_true!(chip.poll_interrupt().map(|s| s & ISL29125_STATUS_THRESHOLD == 0).unwrap_or(false), "status_clean", passed, failed);
    let _ = chip.set_mode(Isl29125Mode::PowerDown);
    let _ = chip.soft_reset(&mut delay);
    let _ = chip.set_mode(Isl29125Mode::Rgb);
    check_true!(chip.device_id().ok() == Some(0x7D), "soft_reset_id", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
