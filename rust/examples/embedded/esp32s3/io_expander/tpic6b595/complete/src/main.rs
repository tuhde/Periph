#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::{Level, Output, OutputConfig};
use esp_hal::spi::master::{Config as SpiConfig, Spi};
use esp_hal::spi::Mode as SpiMode;
use esp_hal::time::Rate;
use esp_println::println;
use periph::chips::io_expander::{Tpic6b595Minimal, Tpic6b595Full};
use embedded_hal::digital::{OutputPin, StatefulOutputPin};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_cfg = SpiConfig::default()
        .with_frequency(Rate::from_mhz(1))
        .with_mode(SpiMode::Mode0);

    // --- Tpic6b595Minimal ---
    let spi_min = Spi::new(peripherals.SPI2, spi_cfg).unwrap().with_sck(peripherals.GPIO18).with_mosi(peripherals.GPIO23);
    let rck_min = Output::new(peripherals.GPIO17, Level::Low, OutputConfig::default());

    let chip1 = Tpic6b595Minimal::new(spi_min, rck_min, None::<Output<'_>>, None::<Output<'_>>, 1).expect("init TPIC6B595 minimal");   // Create TPIC6B595 minimal driver, (spi, rck, srclr=None, g=None, num_devices=1) → Result

    let mut p0 = chip1.pin(0);                                          // Get pin proxy, (n=0) → ExPin
    p0.set_high().expect("set_high");                                   // Set DMOS output ON, () → Result<(), E>
                                                                          // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
    p0.set_low().expect("set_low");                                     // Set DMOS output OFF, () → Result<(), E>
                                                                          // clears shadow[0] bit 0, retransmits and latches

    let set_high = p0.is_set_high().expect("is_set_high");             // Read shadow bit, () → Result<bool, E>
                                                                          // returns the shadow bit (no bus read — SiPo is write-only)
    println!("pin0 is_set_high={}", set_high);

    chip1.fill(true).expect("fill true");                              // Set every output ON, (value=true) → Result<(), E>
                                                                          // fills every shadow byte with 0xFF and retransmits — fast "all on" path
    chip1.fill(false).expect("fill false");                            // Set every output OFF, (value=false) → Result<(), E>
                                                                          // fills every shadow byte with 0x00 and retransmits — fast "all off" path
    chip1.off().expect("off");                                         // Turn every output off, () → Result<(), E>
                                                                          // shorthand for fill(false); the safe initial state

    chip1.write_port(0, 0xA5).expect("write_port");                    // Write port 0, (port=0, mask=0xA5) → Result<(), E>
                                                                          // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF

    // --- Tpic6b595Full ---
    // A second, independently wired cascade on SPI3 — SPI2/GPIO17 above are
    // already owned by chip1's Minimal driver instance.
    let spi_full = Spi::new(peripherals.SPI3, spi_cfg).unwrap().with_sck(peripherals.GPIO12).with_mosi(peripherals.GPIO13);
    let rck   = Output::new(peripherals.GPIO14, Level::Low, OutputConfig::default());
    let srclr = Output::new(peripherals.GPIO27, Level::High, OutputConfig::default());
    let g     = Output::new(peripherals.GPIO26, Level::Low, OutputConfig::default());

    let mut chip2 = Tpic6b595Full::new(spi_full, rck, Some(srclr), Some(g), 2).expect("init TPIC6B595 full"); // Create TPIC6B595 full driver, (spi, rck, srclr, g, num_devices=2) → Result
                                                                          // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

    chip2.write_all(&[0x01, 0x80]).expect("write_all");                 // Write all device bytes, (values=[0x01, 0x80]) → Result<(), E>
                                                                          // updates both shadow bytes and performs one transmit + latch

    chip2.clear().expect("clear");                                     // Pulse SRCLR, () → Result<(), E>
                                                                          // clears the shift register only; outputs unaffected until next RCK pulse
    chip2.set_output_enable(false).expect("set_output_enable false");  // Force every output off via G, (enabled=false) → Result<(), E>
                                                                          // drives G HIGH, blanking outputs without disturbing the shadow register
    chip2.set_output_enable(true).expect("set_output_enable true");    // Re-enable outputs, (enabled=true) → Result<(), E>
                                                                          // drives G LOW; outputs resume from the previously-latched state

    loop {}
}
