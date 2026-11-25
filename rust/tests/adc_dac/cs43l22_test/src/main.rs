use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::cs43l22::*;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR").ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x4A);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut dac = CS43L22Full::new(dev, addr);
    dac.init().expect("init");
    check_true!(true, "init", passed, failed);
    check_true!(dac.read_id().expect("id").0 == 0x1C, "read_id", passed, failed);
    dac.set_volume(-20.0).expect("set");
    check_true!((dac.get_volume().expect("get") - -20.0).abs() < 0.01, "volume_roundtrip", passed, failed);
    dac.set_volume_channels(-10.0, -12.0).expect("set");
    check_true!(dac.read_register(0x20).expect("get") == 0xEC && dac.read_register(0x21).expect("get") == 0xE8, "volume_channels", passed, failed);
    dac.set_headphone_gain(GAIN_1_000).expect("set");
    check_true!(dac.headphone_gain().expect("get") == 1.0, "headphone_gain", passed, failed);
    dac.mute(true).expect("mute");
    check_true!(dac.read_register(0x0F).expect("get") & 0xC0 == 0xC0, "mute", passed, failed);
    dac.mute(false).expect("mute");
    check_true!(dac.read_register(0x0F).expect("get") & 0xC0 == 0, "unmute", passed, failed);
    dac.set_tone(6.0, 6.0, 0, 0, true).expect("set");
    check_true!(dac.read_register(0x1F).expect("get") == 0x44, "tone", passed, failed);
    dac.set_limiter(&Limiter { enabled: true, max_db: -6, cushion_db: -12, ..Limiter::default() }).expect("set");
    check_true!(dac.read_register(0x28).expect("get") & 0x80 == 0x80, "limiter", passed, failed);
    dac.set_limiter(&Limiter::default()).expect("set");
    check_true!(dac.read_speaker_status().is_ok(), "speaker_status_readable", passed, failed);
    dac.stop(&mut Delay).expect("stop");
    check_true!(dac.read_register(0x02).expect("get") == 0x9F, "stop_powers_down", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 {
        std::process::exit(1);
    }
}
