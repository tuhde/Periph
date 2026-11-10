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
use periph::chips::motor::{Tmc2130Full, Tmc2130NoPin};

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
    let step = Output::new(peripherals.GPIO7, esp_hal::gpio::Level::Low, esp_hal::gpio::OutputConfig::default());
    let dir = Output::new(peripherals.GPIO8, esp_hal::gpio::Level::Low, esp_hal::gpio::OutputConfig::default());
    let en = Output::new(peripherals.GPIO9, esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());

    let mut passed = 0u32;
    let mut failed = 0u32;

    let mut motor = Tmc2130Full::new(device, step, dir, Delay::new(), 0.11, Some(en), None::<Tmc2130NoPin>, None::<Tmc2130NoPin>)
        .expect("init TMC2130"); // Create TMC2130 Full driver, (spi, step_pin, dir_pin, delay, r_sense_ohm=0.11 Ω, en_pin, diag0_pin, diag1_pin) → Result

    let ioin = motor.read_ioin().expect("ioin"); // Read IOIN, () → Result<Tmc2130Ioin, Error>
    if ioin.version == 0x11 { println!("PASS ioin_version"); passed += 1; } else { println!("FAIL ioin_version"); failed += 1; }

    motor.write_register(0x13, 600, true).expect("verified write"); // Write a raw register, (reg, value, verify) → Result<(), Error>
    println!("PASS verified_write");
    passed += 1;

    motor.set_current(0.5).expect("current"); // Set run current, (rms_a A RMS) → Result<(), Error>
    motor.set_microsteps(16).expect("microsteps"); // Set microstep resolution, (n 1–256) → Result<(), Error>
    let chop = motor.read_register(0x6C).expect("chopconf"); // Read a raw register, (reg) → Result<u32, Error>
    if (chop >> 24) & 0xF == 4 { println!("PASS mres_readback"); passed += 1; } else { println!("FAIL mres_readback"); failed += 1; }

    motor.enable().expect("enable"); // Power stage on, () → Result<(), Error>
    motor.move_steps(50, 100.0).expect("move"); // Move, (full_steps, full_steps_per_s) → Result<(), Error>
    if motor.read_fault().expect("fault") == 0 { println!("PASS no_fault"); passed += 1; } else { println!("FAIL no_fault"); failed += 1; }
    motor.set_stall_threshold(0).expect("sgt"); // Set StallGuard2 threshold, (sgt -64..63) → Result<(), Error>
    if motor.read_stallguard().expect("sg") <= 1023 { println!("PASS sg_result_range"); passed += 1; } else { println!("FAIL sg_result_range"); failed += 1; }
    motor.disable().expect("disable"); // Power stage off, () → Result<(), Error>

    println!("===DONE: {} passed, {} failed===", passed, failed);
    loop {}
}
