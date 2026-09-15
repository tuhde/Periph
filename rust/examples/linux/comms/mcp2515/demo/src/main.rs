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
use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::comms::{MCP2515Full, OPMOD_LOOPBACK, OPMOD_NORMAL};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn main() {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev_path = format!("/dev/spidev{}.{}", bus, dev);
    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(5_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut chip = MCP2515Full::new(device, 125, 8).expect("init MCP2515");    // Create MCP2515 full driver, (spi, bitrate_kbps=125, osc_mhz=8) → Result

    // --- Switch the chip into Loopback mode so sent frames echo internally ---
    // Loopback bypasses the CAN bus entirely - transmitted frames are routed
    // straight into RXB0/RXB1 - which lets us demonstrate the full TX->RX
    // round trip on a single board with no CAN transceiver attached.
    chip.set_mode(OPMOD_LOOPBACK).expect("loopback");                              // Enter loopback mode, (mode=OPMOD_LOOPBACK) → Result<()>

    let start = std::time::SystemTime::now();

    // --- Main loop: send one heartbeat per second, drain the RX queue ---
    // The heartbeat uses standard ID 0x001 and a 4-byte payload of the uptime
    // (seconds since boot, big-endian). The user watches the printed received
    // frames to confirm the loopback path works end-to-end.
    let mut n: u32 = 0;
    loop {
        let uptime_secs = start.elapsed().map(|d| d.as_secs() as u32).unwrap_or(0);
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
                    let hex: String = frame.data[..frame.dlc as usize]
                        .iter().map(|b| format!("{:02X}", b)).collect();
                    println!(
                        "[{}] rx {} id=0x{:03X}{} dlc={} data=[{}]",
                        n, kind, frame.id, rtr, frame.dlc, hex,
                    );
                }
                None => break,
            }
        }

        n += 1;
        std::thread::sleep(std::time::Duration::from_millis(1000));
    }

    // Unreachable in the demo loop; keep the compiler happy.
    let _ = OPMOD_NORMAL;
}
