//! Si1145 hardware-in-loop test for Linux.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::si1145;
use periph::chips::light::Si1145Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let mut passed = 0i32;
    let mut failed = 0i32;
    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut sensor = Si1145Full::new(dev, addr);

    check_true!(sensor.init(&mut delay).is_ok(), "init");
    let (part, _rev, _seq) = sensor.read_id().expect("id");
    check_true!((0x45..=0x47).contains(&part), "part_id");
    check_true!(sensor.read_response().map(|r| r.error == 0).unwrap_or(false), "response_no_error");
    let r = sensor.read(&mut delay).expect("read");
    check_true!(r.uv_index >= 0.0 && r.uv_index < 20.0, "read_uv_index_range");
    check_true!(r.visible > 0, "read_visible_positive");
    check_true!(r.infrared > 0, "read_infrared_positive");
    check_true!(sensor.read_proximity(&mut delay, 1).is_ok(), "read_proximity_single");
    check_true!(sensor.read_uv_index(&mut delay).map(|u| u < 20.0).unwrap_or(false), "read_uv_index_single");
    check_true!(sensor.read_visible(&mut delay).map(|v| v > 0).unwrap_or(false), "read_visible_single");
    check_true!(sensor.read_infrared(&mut delay).map(|v| v > 0).unwrap_or(false), "read_infrared_single");
    check_true!(sensor.read_raw().is_ok(), "read_raw");
    check_true!(sensor.read_temperature_raw(&mut delay).map(|v| v > 0).unwrap_or(false), "read_temperature_raw");
    check_true!(sensor.read_vdd_raw(&mut delay).map(|v| v > 0).unwrap_or(false), "read_vdd_raw");
    sensor.set_led_current(1, 0).expect("led");
    let dark = sensor.read_proximity(&mut delay, 1).expect("prox");
    sensor.set_led_current(1, 15).expect("led");
    let lit = sensor.read_proximity(&mut delay, 1).expect("prox");
    check_true!(lit >= dark, "led_current_effect");
    sensor.set_led_current(1, 3).expect("led");
    check_true!(sensor.set_led_current(1, 16).is_err(), "led_level_invalid");
    check_true!(sensor.set_ps_photodiode(&mut delay, 1, true).is_ok(), "set_ps_photodiode");
    sensor.set_gain(&mut delay, 2, 2, 0).expect("gain");
    check_true!(sensor.read_visible(&mut delay).map(|v| v > 0).unwrap_or(false), "gain_read");
    sensor.set_gain(&mut delay, 0, 0, 0).expect("gain");
    sensor.set_high_range(&mut delay, true, true, false).expect("range");
    check_true!(sensor.read_visible(&mut delay).is_ok(), "high_range_read");
    sensor.set_high_range(&mut delay, false, false, false).expect("range");
    check_true!(sensor.set_alignment(&mut delay, false, false, false, false, false).is_ok(), "set_alignment");
    check_true!(sensor.set_uv_coefficients(si1145::UCOEF_DEFAULT).is_ok(), "set_uv_coefficients");
    check_true!(sensor.read_calibration(&mut delay).is_ok(), "read_calibration");
    check_true!(sensor.set_channels(&mut delay, true, true, true, true, false, false, false).is_ok(), "set_channels");
    let _ = sensor.poll_interrupt();
    check_true!(sensor.start_autonomous(&mut delay, 0.05, true, true).is_ok(), "start_autonomous");
    sleep_ms!(500);
    check_true!(sensor.poll_interrupt().map(|s| s & 0x05 != 0).unwrap_or(false), "autonomous_irq");
    check_true!(sensor.pause(&mut delay).is_ok(), "pause");
    check_true!(sensor.force(&mut delay, true, true).is_ok(), "force");
    sleep_ms!(100);
    check_true!(sensor.poll_interrupt().map(|s| s != 0).unwrap_or(false), "force_irq");
    check_true!(sensor.enable_interrupt(si1145::SOURCE_CMD).is_ok(), "enable_interrupt");
    check_true!(sensor.disable_interrupt(si1145::SOURCE_CMD).is_ok(), "disable_interrupt");
    check_true!(sensor.reset(&mut delay).is_ok(), "reset");
    check_true!(sensor.read_response().map(|r| r.error == 0).unwrap_or(false), "reset_no_error");
    check_true!(sensor.read(&mut delay).map(|r| r.visible > 0).unwrap_or(false), "read_after_reset");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
