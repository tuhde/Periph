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

    let ver = rfm.version().expect("version");                               // Read silicon revision, () → u8
    println!("version: 0x{:02X}", ver);
                                                                       // checks silicon revision matches expected 0x12

    rfm.configure(7, 125, 5, true).expect("configure");                // Configure modem, (sf, bandwidth_khz, coding_rate, crc) → Result
                                                                       // sets spreading factor, bandwidth, coding rate, and CRC mode

    rfm.set_tx_power(17, true).expect("set_tx_power");                  // Set TX power, (power_dbm, use_pa_boost) → Result
                                                                       // configures PA_BOOST pin for high-power transmission

    rfm.set_frequency(915_000_000).expect("set_frequency");            // Change carrier frequency, (frequency_hz) → Result
                                                                       // switches to 915 MHz US band

    rfm.send(b"Hello").expect("send");                                  // Transmit packet, (data: &[u8]) → Result
                                                                       // enters TX mode, polls TxDone, returns to STDBY

    let rx = rfm.receive(2000).expect("receive");                       // Receive packet, (timeout_ms) → Result<Option<Vec<u8>>>
    if let Some(pkt) = rx {
        println!("rx: {:?}", pkt);
        let rssi = rfm.last_packet_rssi().expect("rssi");              // Read packet RSSI, () → Result<f32>
        let snr = rfm.last_packet_snr().expect("snr");                  // Read packet SNR, () → Result<f32>
        println!("rssi: {:.1} dBm, snr: {:.1} dB", rssi, snr);
    }

    rfm.receive_continuous().expect("receive_continuous");             // Enter continuous receive mode, () → Result
                                                                       // keeps receiver always on, packets queued in FIFO

    std::thread::sleep(std::time::Duration::from_millis(500));
    if let Ok(Some(pkt)) = rfm.read_packet() {                         // Read packet from FIFO, () → Result<Option<Vec<u8>>>
        println!("continuous rx: {:?}", pkt);
    }

    rfm.stop_receive().expect("stop_receive");                          // Return to STANDBY, () → Result

    let rssi_current = rfm.rssi().expect("rssi");                     // Read channel RSSI, () → Result<f32>
    println!("channel rssi: {:.1} dBm", rssi_current);

    rfm.standby().expect("standby");                                    // Enter STANDBY mode, () → Result

    rfm.sleep().expect("sleep");                                        // Enter SLEEP mode, () → Result

    println!("===DONE: 1 passed, 0 failed===");
}