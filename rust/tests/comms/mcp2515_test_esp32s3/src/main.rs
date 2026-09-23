#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::comms::{
    MCP2515Full, OPMOD_NORMAL, OPMOD_LOOPBACK,
};

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let spi_bus = Spi::new(peripherals.SPI2, Config::default())
        .unwrap()
        .with_mosi(peripherals.GPIO3)
        .with_miso(peripherals.GPIO4)
        .with_sck(peripherals.GPIO5);
    let cs = Output::new(peripherals.GPIO6, esp_hal::gpio::Level::High, esp_hal::gpio::OutputConfig::default());
    let device = ExclusiveDevice::new_no_delay(spi_bus, cs).unwrap();

    let mut passed = 0u32;
    let mut failed = 0u32;

    let mut chip = MCP2515Full::new(device, 125, 8).expect("init MCP2515");    // Create MCP2515 full driver, (spi, bitrate_kbps=125, osc_mhz=8) → Result

    let mode = chip.get_mode().unwrap_or(0xFF);                                 // Read current OPMOD, () → Result<u8>
    if mode == OPMOD_NORMAL { println!("PASS mode_is_normal_after_init"); passed += 1; }
    else                    { println!("FAIL mode_is_normal_after_init"); failed += 1; }

    chip.set_one_shot(false).expect("set_one_shot(false)");                     // Disable one-shot mode, (enable=false) → Result<()>
    chip.set_one_shot(true).expect("set_one_shot(true)");                       // Enable one-shot mode, (enable=true) → Result<()>
    chip.set_one_shot(false).expect("set_one_shot(false)");                     // Disable one-shot mode, (enable=false) → Result<()>
    println!("PASS set_one_shot_accepted"); passed += 1;

    chip.set_mask(0, 0, false).expect("set_mask(0)");                           // Configure mask 0, (mask_num=0, mask=0, extended=false) → Result<()>
    chip.set_mask(1, 0, false).expect("set_mask(1)");                           // Configure mask 1, (mask_num=1, mask=0, extended=false) → Result<()>
    println!("PASS set_mask_accepted"); passed += 1;

    chip.set_filter(0, 0, false).expect("set_filter(0)");                       // Configure filter 0, (filter_num=0, id=0, extended=false) → Result<()>
    chip.set_filter(5, 0x7FF, false).expect("set_filter(5)");                   // Configure filter 5, (filter_num=5, id=0x7FF, extended=false) → Result<()>
    println!("PASS set_filter_accepted"); passed += 1;

    chip.set_rx_mode(0, 3).expect("set_rx_mode(0,3)");                         // Set RXB0 filter mode, (buf=0, mode=3=accept_all) → Result<()>
    chip.set_rx_mode(1, 3).expect("set_rx_mode(1,3)");                         // Set RXB1 filter mode, (buf=1, mode=3=accept_all) → Result<()>
    println!("PASS set_rx_mode_accepted"); passed += 1;

    chip.set_mode(OPMOD_LOOPBACK).expect("set_mode(loopback)");                 // Enter loopback mode, (mode=OPMOD_LOOPBACK) → Result<()>
    if chip.get_mode().unwrap_or(0xFF) == OPMOD_LOOPBACK { println!("PASS mode_is_loopback"); passed += 1; }
    else { println!("FAIL mode_is_loopback"); failed += 1; }

    let buf = chip.send_buffered(0x123, &[0x01, 0x02], false, 0).expect("send_buffered");  // Send on TXB0, (id=0x123, data, extended=false, buf=0) → Result<u8>
    if buf <= 2 { println!("PASS send_buffered_returns_valid_buf"); passed += 1; }
    else { println!("FAIL send_buffered_returns_valid_buf"); failed += 1; }

    let _ = chip.recv(50).expect("recv");                                       // Poll for received frame, (timeout_ms=50) → Result<Option<CanFrame>>
    println!("PASS recv_accepted"); passed += 1;

    let errors = chip.read_errors().expect("read_errors");                      // Read TEC/REC/EFLG, () → Result<(u8, u8, u8)>
    if errors.0 < 0xFF && errors.1 < 0xFF { println!("PASS read_errors_in_range"); passed += 1; }
    else { println!("FAIL read_errors_in_range"); failed += 1; }

    chip.abort_tx().expect("abort_tx");                                         // Abort pending TX, () → Result<()>
    println!("PASS abort_tx_accepted"); passed += 1;

    chip.clear_overflow(0).expect("clear_overflow(0)");                         // Clear RXB0 overflow flag, (buf=0) → Result<()>
    chip.clear_overflow(1).expect("clear_overflow(1)");                         // Clear RXB1 overflow flag, (buf=1) → Result<()>
    println!("PASS clear_overflow_accepted"); passed += 1;

    chip.reset().expect("reset");                                               // Issue SPI RESET, () → Result<()>
    println!("PASS reset_accepted"); passed += 1;

    chip.set_mode(OPMOD_NORMAL).expect("set_mode(normal)");                     // Enter normal mode, (mode=OPMOD_NORMAL) → Result<()>
    if chip.get_mode().unwrap_or(0xFF) == OPMOD_NORMAL { println!("PASS mode_is_normal_again"); passed += 1; }
    else { println!("FAIL mode_is_normal_again"); failed += 1; }

    println!("===DONE: {} passed, {} failed===", passed, failed);

    let mut delay = Delay::new();
    delay.delay_millis(250);
    loop {}
}
