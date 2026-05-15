use linux_embedded_hal::I2cdev;
use periph::chips::comms::rfm9x::RFM95Full;

fn main() {
    let spi_bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let spi_device: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);

    let dev = spidev::Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device)).expect("open spi");
    let mut spi = spidev::SpidevOptions::new()
        .max_speed_hz(5_000_000)
        .mode(spidev::SpiModeFlags::SPI_MODE_0)
        .build();
    spi.configure(&dev).expect("configure spi");
    let cs_pin = 0;
    let device = embedded_hal_bus::spi::ExclusiveDevice::new_no_delay(spi, cs_pin).expect("exclusive device");

    let mut rfm = RFM95Full::new(device, 868_000_000).expect("init RFM95");  // Create RFM95 driver, (spi, frequency_hz=868 MHz)

    let ver = rfm.version().expect("version");                          // Read silicon revision, () → u8
    println!("version: 0x{:02X}", ver);

    rfm.send(b"Hello").expect("send");                                  // Transmit packet, (data: &[u8]) → Result
    println!("sent");

    rfm.standby().expect("standby");                                    // Enter STANDBY mode, () → Result

    rfm.sleep().expect("sleep");                                        // Enter SLEEP mode, () → Result

    println!("===DONE: 1 passed, 0 failed===");
}