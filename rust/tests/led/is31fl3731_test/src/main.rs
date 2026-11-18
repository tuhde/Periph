use linux_embedded_hal::I2cdev;
use periph::chips::led::is31fl3731::{Is31fl3731Full, Is31fl3731Minimal, COLS, ROWS};

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
        .unwrap_or(0x74);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut matrix = Is31fl3731Minimal::new(dev, addr).expect("init IS31FL3731");
    check_true!(ROWS == 9 && COLS == 16, "constants", passed, failed);

    check_true!(matrix.set_pixel(0, 0, 255).unwrap_or(false) && matrix.set_pixel(8, 15, 64).unwrap_or(false), "set_pixel", passed, failed);
    check_true!(!matrix.set_pixel(9, 0, 0).unwrap_or(true) && !matrix.set_pixel(0, 16, 0).unwrap_or(true), "set_pixel_range", passed, failed);
    check_true!(matrix.fill(32).is_ok(), "fill", passed, failed);
    check_true!(matrix.clear().is_ok(), "clear", passed, failed);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Is31fl3731Full::new(dev, addr).expect("init IS31FL3731");
    check_true!(chip.set_pixel(3, 4, 99).is_ok() && chip.get_pixel(3, 4) == 99 && chip.get_pixel(3, 5) == 0, "get_pixel", passed, failed);
    let mut ramp = [0u8; 144];
    for (i, v) in ramp.iter_mut().enumerate() { *v = i as u8; }
    check_true!(chip.set_pixels(&ramp).unwrap_or(false) && !chip.set_pixels(&ramp[..143]).unwrap_or(true), "set_pixels", passed, failed);
    check_true!(chip.set_led_enabled(0, 0, false).is_ok() && chip.set_led_enabled(0, 0, true).is_ok(), "set_led_enabled", passed, failed);
    check_true!(chip.set_blink(1, 1, true).is_ok() && chip.set_blink_all(true).is_ok(), "set_blink", passed, failed);
    check_true!(chip.enable_blink(true, 2).unwrap_or(false) && !chip.enable_blink(true, 8).unwrap_or(true), "enable_blink", passed, failed);
    chip.enable_blink(false, 1).ok();
    chip.set_blink_all(false).ok();
    check_true!(chip.select_frame(1).unwrap_or(false) && !chip.select_frame(8).unwrap_or(true), "select_frame", passed, failed);
    chip.fill(10).ok();
    check_true!(chip.copy_frame(1, 2).unwrap_or(false) && !chip.copy_frame(1, 8).unwrap_or(true), "copy_frame", passed, failed);
    check_true!(chip.show_frame(2).unwrap_or(false) && !chip.show_frame(8).unwrap_or(true), "show_frame", passed, failed);
    chip.select_frame(0).ok();
    chip.show_frame(0).ok();
    check_true!(chip.clear_frame(1).unwrap_or(false) && chip.clear_all_frames().is_ok(), "clear_frames", passed, failed);
    check_true!(chip.set_picture_mode().is_ok(), "set_picture_mode", passed, failed);
    check_true!(chip.start_auto_play(2, 1, 110, 0).unwrap_or(false) && !chip.start_auto_play(0, 0, 2000, 0).unwrap_or(true), "start_auto_play", passed, failed);
    check_true!(chip.current_frame().is_ok() && chip.movie_finished().is_ok(), "frame_state", passed, failed);
    check_true!(chip.stop_auto_play().is_ok(), "stop_auto_play", passed, failed);
    let audio_ok = chip.set_audio_mode(false).is_ok() && chip.set_audio_sync(false).is_ok() && chip.set_audio_rate(10).is_ok()
        && chip.set_audio_gain(6, true, false).unwrap_or(false) && !chip.set_audio_gain(4, false, false).unwrap_or(true);
    check_true!(audio_ok, "audio", passed, failed);
    check_true!(chip.set_breath(true, 2, 2, 1).unwrap_or(false) && chip.set_breath(false, 0, 0, 0).unwrap_or(false), "set_breath", passed, failed);
    check_true!(chip.set_intensity_control(true).is_ok() && chip.set_intensity_control(false).is_ok(), "set_intensity_control", passed, failed);
    check_true!(chip.write_function_register(0x03, 0x05).unwrap_or(false) && chip.read_function_register(0x03).is_ok(), "function_register", passed, failed);
    check_true!(chip.poll_interrupt().is_ok(), "poll_interrupt", passed, failed);
    check_true!(chip.shutdown(true).is_ok() && chip.shutdown(false).is_ok(), "shutdown", passed, failed);
    check_true!(chip.clear().is_ok(), "clear_all", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
