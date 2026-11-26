//! Si1133 hardware-in-loop test for ESP32-S3.
//! Wiring: GPIO1 -> SDA, GPIO2 -> SCL; address 0x55, INT pulled up.
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_hal::main;
use esp_hal::time::Rate;
use periph::chips::light::si1133;
use periph::chips::light::Si1133Full;

esp_app_desc!();

#[main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default().with_frequency(Rate::from_khz(100)))
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let mut chip = Si1133Full::new(i2c, 0x55);

    esp_println::println!("Si1133 test started");

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { esp_println::println!("PASS {}", $label); passed += 1; }
            else      { esp_println::println!("FAIL {}", $label); failed += 1; }
        };
    }

    chip.init(&mut delay).expect("init");
    let (part, hw, _rev) = chip.read_id().expect("id");
    check_true!(part == 0x33, "part_id");
    check_true!(hw & 0x1F == 0x03, "hw_id_impl");
    check_true!(!chip.read_response().expect("response").error, "response_no_error");
    let m = chip.read(&mut delay).expect("read");
    check_true!(m.uv >= -0x80_0000 && m.uv <= 0x7F_FFFF, "read_uv_range");
    check_true!(m.white > 0, "read_white_positive_in_light");
    check_true!(chip.read_uv(&mut delay).is_ok(), "read_uv_scratch");
    check_true!(chip.read_white(&mut delay).expect("white") > 0, "read_white_scratch");
    check_true!(chip.read_ir(&mut delay, si1133::MUX_SMALL_IR).is_ok(), "read_ir_small");
    check_true!(chip.read_ir(&mut delay, si1133::MUX_LARGE_IR).is_ok(), "read_ir_large");
    chip.configure_channel(&mut delay, 2, si1133::MUX_LARGE_IR, 5, 0, 0, false, false, 0).expect("configure");
    chip.enable_channels(&mut delay, 0x07).expect("enable");
    let c = chip.read_channels(&mut delay, true).expect("channels");
    check_true!(c.count == 3, "read_channels_count");
    check_true!(c.values[2] >= 0 && c.values[2] <= 0xFFFF, "read_channels_16bit");
    chip.write_parameter(&mut delay, 0x1C, 0x05).expect("write_parameter");
    check_true!(chip.read_parameter(&mut delay, 0x1C).expect("read_parameter") == 0x05, "parameter_roundtrip");
    check_true!(chip.read_parameter(&mut delay, 0x01).expect("read_parameter") == 0x07, "chan_list_readback");
    chip.set_threshold(&mut delay, 0, 1000).expect("threshold");
    chip.set_channel_threshold(&mut delay, 0, 1).expect("channel_threshold");
    let hi = chip.read_parameter(&mut delay, 0x25).expect("read_parameter");
    let lo = chip.read_parameter(&mut delay, 0x26).expect("read_parameter");
    check_true!(hi == 0x03 && lo == 0xE8, "threshold_readback");
    chip.set_channel_threshold(&mut delay, 0, 0).expect("channel_threshold");
    chip.enable_channels(&mut delay, 0x03).expect("enable");
    chip.data_ready().expect("data_ready");
    chip.start_autonomous(&mut delay, 0.1, [1, 1, 1]).expect("start");
    sleep_ms!(500);
    check_true!(chip.poll_interrupt().expect("poll") & 0x03 != 0, "autonomous_irq");
    check_true!(chip.read_channels(&mut delay, false).expect("channels").count == 2, "autonomous_values");
    chip.pause(&mut delay).expect("pause");
    check_true!(chip.reset(&mut delay).is_ok(), "reset");
    check_true!(chip.read_response().expect("response").state & si1133::RESP_SLEEP != 0, "reset_sleep");
    check_true!(chip.read(&mut delay).expect("read").white > 0, "read_after_reset");

    esp_println::println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {
        sleep_ms!(1000);
    }
}
