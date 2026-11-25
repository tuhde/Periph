use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::{Apds9250Channel, Apds9250Full, APDS9250_I2C_ADDRESS, APDS9250_SOURCE_VARIATION, APDS9250_STATUS_INTERRUPT};

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
        .unwrap_or(APDS9250_I2C_ADDRESS);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Apds9250Full::new(dev, addr);
    chip.init(&mut delay).expect("init");

    check_true!(chip.part_id().map(|p| p.0 == 0xB).unwrap_or(false), "part_id", passed, failed);
    check_true!(chip.get_resolution().ok() == Some(18) && chip.get_measurement_rate().ok() == Some(100) && chip.get_gain().ok() == Some(3), "defaults", passed, failed);
    let c = chip.read(&mut delay).expect("read");
    check_true!(c.red <= 0xFFFFF && c.green <= 0xFFFFF && c.blue <= 0xFFFFF && c.ir <= 0xFFFFF, "read_range", passed, failed);
    check_true!(chip.set_gain(18).is_ok() && chip.get_gain().ok() == Some(18), "gain_18", passed, failed);
    let _ = chip.set_gain(3);
    check_true!(chip.set_resolution(16).is_ok() && chip.get_resolution().ok() == Some(16), "resolution_16", passed, failed);
    check_true!(chip.integration_time().map(|t| (t - 0.025).abs() < 1e-4).unwrap_or(false), "integration_time", passed, failed);
    let _ = chip.set_resolution(18);
    check_true!(chip.set_measurement_rate(200).is_ok() && chip.get_measurement_rate().ok() == Some(200), "rate_200", passed, failed);
    let _ = chip.set_measurement_rate(100);
    check_true!(chip.read_channels().map(|c| c.green <= 0xFFFFF).unwrap_or(false), "read_channels_range", passed, failed);
    check_true!(chip.read_green().map(|v| v <= 0xFFFFF).unwrap_or(false), "read_single_green", passed, failed);
    let _ = chip.set_mode(false);
    check_true!(chip.read_ir().map(|v| v <= 0xFFFFF).unwrap_or(false), "als_mode_reads", passed, failed);
    let _ = chip.set_mode(true);
    check_true!(chip.set_thresholds(100, 200_000).is_ok() && chip.get_thresholds().ok() == Some((100, 200_000)), "thresholds_roundtrip", passed, failed);
    check_true!(chip.set_persistence(3).is_ok() && chip.set_variance(64).is_ok() && chip.set_interrupt_channel(Apds9250Channel::Green).is_ok(), "interrupt_config", passed, failed);
    let _ = chip.enable_interrupt(APDS9250_SOURCE_VARIATION);
    let _ = chip.disable_interrupt();
    let _ = chip.status();
    check_true!(chip.poll_interrupt().map(|s| s & APDS9250_STATUS_INTERRUPT == 0).unwrap_or(false), "status_clean", passed, failed);
    let _ = chip.disable();
    let _ = chip.enable();
    let _ = chip.soft_reset(&mut delay);
    check_true!(chip.get_gain().ok() == Some(3) && chip.get_resolution().ok() == Some(18), "soft_reset_defaults", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
