// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
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
use periph::chips::io_expander::Tpic6b595Full;

esp_app_desc!();

const NUM_DEVICES: u8 = 2;
const NUM_OUTPUTS: i32 = (NUM_DEVICES as i32) * 8;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());
    let mut delay = Delay::new();

    let spi_cfg = SpiConfig::default()
        .with_frequency(Rate::from_mhz(1))
        .with_mode(SpiMode::Mode0);
    let spi = Spi::new(peripherals.SPI2, spi_cfg)
        .unwrap()
        .with_sck(peripherals.GPIO18)
        .with_mosi(peripherals.GPIO23);
    let rck   = Output::new(peripherals.GPIO17, Level::Low, OutputConfig::default());
    let srclr = Output::new(peripherals.GPIO16, Level::High, OutputConfig::default());
    let g     = Output::new(peripherals.GPIO15, Level::Low, OutputConfig::default());

    let mut chip = Tpic6b595Full::new(spi, rck, Some(srclr), Some(g), NUM_DEVICES) // Create TPIC6B595 full driver, (spi, rck, srclr, g, num_devices=2) → Result
        .expect("init TPIC6B595 full");

    let mut position: i32 = 0;
    let mut direction: i32 = 1;
    let mut sweep_count: u32 = 0;
    const BLANK_EVERY: u32 = 3;
    const BLANK_MS: u32   = 500;

    loop {
        // --- Walk a single lit LED across all 16 outputs and back ---
        // Use write_all() each step so both cascaded devices latch together —
        // there is no way to update just one downstream device without re-sending
        // the whole chain's data.
        let mut bytes = [0u8; NUM_DEVICES as usize];
        let port = (position / 8) as usize;
        let bit  = (position % 8) as u8;
        bytes[port] = 1u8 << bit;
        chip.write_all(&bytes).expect("write_all");                                  // Write all device bytes, (values=[0x01, 0x80]) → Result<(), E>

        println!("position={}  bytes=[0x{:02X}, 0x{:02X}]", position, bytes[0], bytes[1]);

        // --- Periodically blank every output via G, then resume ---
        // set_output_enable(false) drives G HIGH, forcing every DMOS off without
        // touching the shadow register — the LEDs simply resume exactly where they
        // left off when G is re-enabled.
        sweep_count += 1;
        if sweep_count % BLANK_EVERY == 0 {
            chip.set_output_enable(false).expect("set_output_enable false");         // Force every output off via G, (enabled=false) → Result<(), E>
                                                                                      // the chase pattern's shadow state is preserved
            println!("  blanked via G for {} ms", BLANK_MS);
            delay.delay_ms(BLANK_MS);
            chip.set_output_enable(true).expect("set_output_enable true");            // Re-enable outputs, (enabled=true) → Result<(), E>
                                                                                      // LEDs resume from the previously-latched state
        }

        // Bounce the chase position at both ends of the strip
        position += direction;
        if position >= NUM_OUTPUTS - 1 || position <= 0 {
            direction = -direction;
            delay.delay_ms(100);
        } else {
            delay.delay_ms(80);
        }
    }
}
