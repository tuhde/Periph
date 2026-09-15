/* MCP2515 demo — loopback heartbeat.
 *
 * Hardware: a single MCP2515 module on the SPI bus. The driver puts the chip
 * in Loopback mode (no external CAN bus or second MCP2515 needed) and sends
 * one standard-ID frame per second with a 4-byte payload containing the
 * uptime in seconds (big-endian). Every received frame is decoded and
 * printed with its ID (hex), DLC, data (hex bytes), and frame type.
 *
 * In Loopback mode the sent heartbeat is echoed back internally by the
 * chip, so this single-board demo exercises the full TX → RX round trip.
 */
#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::comms::{MCP2515Full, OPMOD_LOOPBACK};

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

    // --- Switch the chip into Loopback mode so sent frames echo internally ---
    // Loopback bypasses the CAN bus entirely - transmitted frames are routed
    // straight into RXB0/RXB1 - which lets us demonstrate the full TX->RX
    // round trip on a single board with no CAN transceiver attached.
    chip.set_mode(OPMOD_LOOPBACK).expect("loopback");                              // Enter loopback mode, (mode=OPMOD_LOOPBACK) → Result<()>

    // --- Main loop: send one heartbeat per second, drain the RX queue ---
    // The heartbeat uses standard ID 0x001 and a 4-byte payload of the uptime
    // (seconds since boot, big-endian). The user watches the printed received
    // frames to confirm the loopback path works end-to-end.
    let mut delay = Delay::new();
    let mut n: u32 = 0;
    let mut uptime_secs: u32 = 0;
    loop {
        let payload = uptime_secs.to_be_bytes();

        chip.send(0x001, &payload, false).expect("send heartbeat");               // Send heartbeat frame, (id=0x001, data, extended=false) → Result<u8>

        // --- Drain all frames currently in the RX buffers before sleeping ---
        // Loopback writes the just-sent frame into RXB0 immediately, but
        // any prior echoes may still be queued; pull everything available
        // and print each one.
        loop {
            let rx = chip.recv(0).expect("recv poll");                            // Poll for received frame, (timeout_ms=0=non-blocking) → Result<Option<CanFrame>>
            match rx {
                Some(frame) => {
                    let kind = if frame.extended { "EXT" } else { "STD" };
                    let rtr  = if frame.rtr { "+RTR" } else { "" };
                    let dlc = (frame.dlc as usize).min(frame.data.len());
                    let b0 = if dlc > 0 { frame.data[0] } else { 0 };
                    let b1 = if dlc > 1 { frame.data[1] } else { 0 };
                    let b2 = if dlc > 2 { frame.data[2] } else { 0 };
                    let b3 = if dlc > 3 { frame.data[3] } else { 0 };
                    println!(
                        "[{}] rx {} id=0x{:03X}{} dlc={} data=[0x{:02X},0x{:02X},0x{:02X},0x{:02X}]",
                        n, kind, frame.id, rtr, frame.dlc, b0, b1, b2, b3,
                    );
                }
                None => break,
            }
        }

        n += 1;
        uptime_secs = uptime_secs.wrapping_add(1);
        delay.delay_ms(1000);
    }
}
