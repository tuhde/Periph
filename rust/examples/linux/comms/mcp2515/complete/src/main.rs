use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::comms::{MCP2515Full, OPMOD_NORMAL, OPMOD_LOOPBACK};
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

    chip.set_filter(0, 0x123, false).expect("filter");                            // Configure filter 0, (filter_num=0, id=0x123, extended=false) → Result<()>
                                                                                   // matches standard ID 0x123
    chip.set_mask(0, 0x7FF, false).expect("mask");                                // Configure mask 0, (mask_num=0, mask=0x7FF, extended=false) → Result<()>
                                                                                   // every standard ID bit must match (exact match)
    chip.set_rx_mode(0, 0).expect("rx_mode(0)");                                  // RXB0 uses standard filter, (buf=0, mode=0=std_filter) → Result<()>
    chip.set_rx_mode(1, 3).expect("rx_mode(1,3)");                                // RXB1 accepts all, (buf=1, mode=3=accept_all) → Result<()>

    chip.set_one_shot(true).expect("one_shot");                                   // Enable one-shot mode, (enable=true) → Result<()>
                                                                                   // no retransmit on error or arbitration loss
    chip.set_one_shot(false).expect("one_shot");                                  // Disable one-shot mode, (enable=false) → Result<()>

    chip.set_mode(OPMOD_LOOPBACK).expect("loopback");                             // Enter loopback mode, (mode=OPMOD_LOOPBACK) → Result<()>
                                                                                   // transmitted frames are received internally; no bus needed

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
}
