#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_hal::spi::Mode;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::motor::{Tmc5160Full, Tmc5160NoPin};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(
        peripherals.SPI2,
        Config::default().with_frequency(Rate::from_mhz(1)).with_mode(Mode::_3),
    )
    .unwrap()
    .with_mosi(peripherals.GPIO3)
    .with_miso(peripherals.GPIO4)
    .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();
    let en = Output::new(peripherals.GPIO9, esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());

    let mut passed = 0u32;
    let mut failed = 0u32;

    let mut motor = Tmc5160Full::new_spi(device, Delay::new(), 0.075, Some(en), None::<Tmc5160NoPin>, None::<Tmc5160NoPin>)
        .expect("init TMC5160"); // Create TMC5160 Full driver, (spi, delay, r_sense_ohm=0.075 Ω, en_pin, diag0_pin, diag1_pin) → Result

    let ioin = motor.read_ioin().expect("ioin"); // Read IOIN, () → Result<Tmc5160Ioin, Error>
    if ioin.version == 0x30 { println!("PASS ioin_version"); passed += 1; } else { println!("FAIL ioin_version"); failed += 1; }

    motor.write_register(0x13, 600, true).expect("verified write"); // Write a raw register, (reg, value, verify) → Result<(), Error>
    println!("PASS verified_write");
    passed += 1;

    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>
    motor.set_position(0.0).expect("origin"); // Define position, (position full steps) → Result<(), Error>
    motor.move_to(100.0).expect("move"); // Start positioning move, (position full steps) → Result<(), Error>
    if motor.wait_until_reached(None).is_ok() { println!("PASS move_reached"); passed += 1; } else { println!("FAIL move_reached"); failed += 1; }
    if motor.read_fault().expect("fault") == 0 { println!("PASS no_fault"); passed += 1; } else { println!("FAIL no_fault"); failed += 1; }
    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
