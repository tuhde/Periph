#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::rfid::Mfrc522Minimal;

esp_app_desc!();

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
    let mut delay = Delay::new();


    let mut mfrc = Mfrc522Minimal::new(device).expect("init MFRC522");       // Create MFRC522 driver, (spi) → Result

    for _ in 0..10 {
        let present = mfrc.is_card_present().expect("is_card_present");      // Detect card in field, () → Result<bool>
        let uid = mfrc.read_uid().expect("read_uid");                        // Read card UID (REQA → anticollision → HLTA), () → Result<Option<(uid, len)>>
        let uid_hex = uid.as_ref().map(|(u, l)| u[..*l].iter().map(|b| format!("{:02X}", b)).collect::<String>()).unwrap_or_default();
        println!("present={} uid={}", present, uid_hex);
        delay.delay_ms(500);
    }
    loop {}
}
