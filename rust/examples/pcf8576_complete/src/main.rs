use linux_embedded_hal::I2cdev;
use periph::chips::display::{Pcf8576Full, SEG_7SEG, BLINK_OFF, BLINK_1HZ, MUX_1_4};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x38);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Pcf8576Full::new(dev, addr).expect("init PCF8576");

    chip.clear().expect("clear");
    chip.set_blink(BLINK_OFF, false).expect("blink off");
    chip.enable().expect("enable");
    chip.set_mode(MUX_1_4, 0).expect("mode");
    chip.device_select(0).expect("device select");
    chip.set_bank(0, 0).expect("bank");
    chip.set_digit_7seg(0, SEG_7SEG[1]).expect("digit 0");
    chip.set_digit_7seg(1, SEG_7SEG[2]).expect("digit 1");
    chip.set_digit_7seg(2, SEG_7SEG[3]).expect("digit 2");
    chip.set_digit_7seg(3, SEG_7SEG[4]).expect("digit 3");
    sleep(Duration::from_secs(1));
    chip.disable().expect("disable");
    sleep(Duration::from_secs(1));
    chip.enable().expect("enable");
    chip.set_blink(BLINK_1HZ, false).expect("blink 1Hz");
}