#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::comms::{MCP2515Full, OPMOD_LOOPBACK, OPMOD_NORMAL};

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

    let mut chip = MCP2515Full::new(device, 125, 8).expect("init MCP2515");    // Create MCP2515 full driver, (spi, bitrate_kbps=125, osc_mhz=8) → Result

    chip.set_filter(0, 0x123, false).expect("filter");                            // Configure filter 0, (filter_num=0, id=0x123, extended=false) → Result<()>
                                                                                   // matches standard ID 0x123
    chip.set_mask(0, 0x7FF, false).expect("mask");                                // Configure mask 0, (mask_num=0, mask=0x7FF, extended=false) → Result<()>
                                                                                   // every standard ID bit must match (exact match)
    chip.set_rx_mode(0, 0).expect("rx_mode(0)");                                  // RXB0 uses standard filter, (buf=0, mode=0=std_filter) → Result<()>
    chip.set_rx_mode(1, 3).expect("rx_mode(1,3)");                                // RXB1 accepts all, (buf=1, mode=3=accept_all) → Result<()>

    chip.set_one_shot(true).expect("one_shot");                                   // Enable one-shot mode, (enable=true) → Result<()>
    chip.set_one_shot(false).expect("one_shot");                                  // Disable one-shot mode, (enable=false) → Result<()>

    chip.set_mode(OPMOD_LOOPBACK).expect("loopback");                             // Enter loopback mode, (mode=OPMOD_LOOPBACK) → Result<()>

    let _buf = chip.send_buffered(0x123, &[0x01, 0x02, 0x03, 0x04], false, 0)   // Send standard frame on TXB0, (id=0x123, data, extended=false, buf=0) → Result<u8>
        .expect("send_buffered");

    let _ = chip.recv(100).expect("recv");                                        // Poll for received frame, (timeout_ms=100) → Result<Option<CanFrame>>

    let (tec, rec, eflg) = chip.read_errors().expect("read_errors");              // Read error counters, () → Result<(tec, rec, eflg)>
    println!("TEC={} REC={} EFLG=0x{:02X}", tec, rec, eflg);

    chip.abort_tx().expect("abort_tx");                                           // Abort any pending TX, () → Result<()>
    chip.clear_overflow(0).expect("clear_overflow(0)");                           // Clear RXB0 overflow flag, (buf=0) → Result<()>
    chip.clear_overflow(1).expect("clear_overflow(1)");                           // Clear RXB1 overflow flag, (buf=1) → Result<()>
    chip.reset().expect("reset");                                                 // Issue SPI RESET, () → Result<()>
    chip.set_mode(OPMOD_NORMAL).expect("normal");                                 // Return to normal mode, (mode=OPMOD_NORMAL) → Result<()>

    let mut delay = Delay::new();
    delay.delay_ms(250);
    loop {}
}
