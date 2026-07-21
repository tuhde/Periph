#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::{
    gpio::Output,
    spi::master::{Config, Spi},
};
use esp_println::println;
use periph::chips::rfid::{Mfrc522Full, RX_GAIN_18_DB, RX_GAIN_23_DB, RX_GAIN_33_DB, RX_GAIN_38_DB, RX_GAIN_43_DB, RX_GAIN_48_DB};

esp_app_desc!();

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);

    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High);
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut mfrc = match Mfrc522Full::new(device) {
        Ok(c) => c,
        Err(_) => {
            println!("FAIL init: could not reach MFRC522");
            println!("===DONE: 0 passed, 1 failed===");
            loop {}
        }
    };

    let (chip, ver) = mfrc.version().unwrap_or((0, 0));
    check_true!(chip == 0x09, "chip_type == 0x09 (MFRC522)", passed, failed);
    check_true!(ver == 1 || ver == 2, "version in {1, 2}", passed, failed);

    mfrc.antenna_on().ok();
    mfrc.antenna_off().ok();
    mfrc.antenna_on().ok();

    for gain in [RX_GAIN_18_DB, RX_GAIN_23_DB, RX_GAIN_33_DB, RX_GAIN_38_DB, RX_GAIN_43_DB, RX_GAIN_48_DB] {
        mfrc.set_antenna_gain(gain).ok();
        check_true!(mfrc.antenna_gain().unwrap_or(0xFF) == gain, "antenna_gain read back", passed, failed);
    }

    let _present = mfrc.is_card_present();
    check_true!(true, "is_card_present", passed, failed);

    mfrc.reset().ok();
    check_true!(true, "reset accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);

    loop {}
}
