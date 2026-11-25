#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_println::println;
use periph::chips::adc_dac::cs43l22::*;

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

const ADDR: u8 = 0x4A;

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let mut delay = Delay::new();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .expect("i2c config")
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut dac = CS43L22Full::new(i2c, ADDR);
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
    dac.stop(&mut delay).expect("stop");
    check_true!(dac.read_register(0x02).expect("get") == 0x9F, "stop_powers_down", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        delay.delay_millis(1000);
    }
}
