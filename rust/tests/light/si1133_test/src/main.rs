//! Si1133 hardware-in-loop test for Linux.
use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::si1133;
use periph::chips::light::Si1133Full;

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
        .unwrap_or(0x55);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }
    let mut chip = Si1133Full::new(dev, addr);

    chip.init(&mut delay).expect("init");
    let (part, hw, _rev) = chip.read_id().expect("id");
    check_true!(part == 0x33, "part_id", passed, failed);
    check_true!(hw & 0x1F == 0x03, "hw_id_impl", passed, failed);
    check_true!(!chip.read_response().expect("response").error, "response_no_error", passed, failed);
    let m = chip.read(&mut delay).expect("read");
    check_true!(m.uv >= -0x80_0000 && m.uv <= 0x7F_FFFF, "read_uv_range", passed, failed);
    check_true!(m.white > 0, "read_white_positive_in_light", passed, failed);
    check_true!(chip.read_uv(&mut delay).is_ok(), "read_uv_scratch", passed, failed);
    check_true!(chip.read_white(&mut delay).expect("white") > 0, "read_white_scratch", passed, failed);
    check_true!(chip.read_ir(&mut delay, si1133::MUX_SMALL_IR).is_ok(), "read_ir_small", passed, failed);
    check_true!(chip.read_ir(&mut delay, si1133::MUX_LARGE_IR).is_ok(), "read_ir_large", passed, failed);
    chip.configure_channel(&mut delay, 2, si1133::MUX_LARGE_IR, 5, 0, 0, false, false, 0).expect("configure");
    chip.enable_channels(&mut delay, 0x07).expect("enable");
    let c = chip.read_channels(&mut delay, true).expect("channels");
    check_true!(c.count == 3, "read_channels_count", passed, failed);
    check_true!(c.values[2] >= 0 && c.values[2] <= 0xFFFF, "read_channels_16bit", passed, failed);
    chip.write_parameter(&mut delay, 0x1C, 0x05).expect("write_parameter");
    check_true!(chip.read_parameter(&mut delay, 0x1C).expect("read_parameter") == 0x05, "parameter_roundtrip", passed, failed);
    check_true!(chip.read_parameter(&mut delay, 0x01).expect("read_parameter") == 0x07, "chan_list_readback", passed, failed);
    chip.set_threshold(&mut delay, 0, 1000).expect("threshold");
    chip.set_channel_threshold(&mut delay, 0, 1).expect("channel_threshold");
    let hi = chip.read_parameter(&mut delay, 0x25).expect("read_parameter");
    let lo = chip.read_parameter(&mut delay, 0x26).expect("read_parameter");
    check_true!(hi == 0x03 && lo == 0xE8, "threshold_readback", passed, failed);
    chip.set_channel_threshold(&mut delay, 0, 0).expect("channel_threshold");
    chip.enable_channels(&mut delay, 0x03).expect("enable");
    chip.data_ready().expect("data_ready");
    chip.start_autonomous(&mut delay, 0.1, [1, 1, 1]).expect("start");
    sleep_ms!(500);
    check_true!(chip.poll_interrupt().expect("poll") & 0x03 != 0, "autonomous_irq", passed, failed);
    check_true!(chip.read_channels(&mut delay, false).expect("channels").count == 2, "autonomous_values", passed, failed);
    chip.pause(&mut delay).expect("pause");
    check_true!(chip.reset(&mut delay).is_ok(), "reset", passed, failed);
    check_true!(chip.read_response().expect("response").state & si1133::RESP_SLEEP != 0, "reset_sleep", passed, failed);
    check_true!(chip.read(&mut delay).expect("read").white > 0, "read_after_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
