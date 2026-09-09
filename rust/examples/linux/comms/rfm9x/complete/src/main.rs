use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::comms::Rfm95Full;
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
    let dev_path = std::env::var("SPI_DEV").unwrap_or_else(|_| "/dev/spidev0.0".to_string());
    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(5_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus, NullCs).expect("spi device");

    let mut radio = Rfm95Full::new(device, 868_000_000).expect("init RFM95");    // Create RFM95W full driver, (spi, frequency_hz=868e6) → Result

    let ver = radio.inner.inner.version().unwrap_or(0xFF);                       // Read silicon version, () → Result<u8>
                                                                                  // expect 0x12 (SX1276)
    println!("version: 0x{:02X}", ver);

    radio.inner.inner.configure(7, 125.0, 5).expect("configure");                  // Configure LoRa modem, (sf=6–12, bandwidth_khz=7.8–500, coding_rate=5–8, crc=true) → Result<()>
                                                                                  // sets SF=7, BW=125 kHz, CR 4/5
    radio.inner.inner.set_tx_power(17, true).expect("tx_power");                   // Set TX power, (power_dbm=2–20, use_pa_boost=true) → Result<()>
    radio.inner.inner.set_frequency(868_000_000).expect("freq");                  // Change carrier frequency, (frequency_hz=862e6–1020e6) → Result<()>
    radio.inner.inner.standby().expect("standby");                                // Enter STDBY mode, () → Result<()>

    radio.inner.inner.send(b"hello world").expect("send");                        // Send packet, (data=&[u8] ≤255 B) → Result<()>
    let _ = radio.inner.inner.receive(2000).expect("receive");                    // Receive single packet, (timeout_ms=2000) → Result<Option<[u8;256]>>

    radio.inner.inner.receive_continuous().expect("rx_cont");                     // Enter continuous RX, () → Result<()>
    radio.inner.inner.stop_receive().expect("stop");                              // Return to STDBY from RX_CONT, () → Result<()>

    radio.inner.inner.sleep().expect("sleep");                                    // Enter SLEEP mode, () → Result<()>
    std::thread::sleep(std::time::Duration::from_millis(250));
    radio.inner.inner.standby().expect("wake");
}
