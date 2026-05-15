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

    let mut rfm = RFM95Full::new(device, 868_000_000).expect("init RFM95");

    // --- Configure for long-range desk link ---
    // SF7 gives good balance of range and data rate; 125 kHz BW is ISM band default;
    // 4/5 coding rate is standard; CRC ensures payload integrity.
    rfm.configure(7, 125, 5, true).expect("configure");                // Configure modem, (sf, bandwidth_khz, coding_rate, crc) → Result

    // +17 dBm is safe maximum for PA_BOOST without active cooling.
    rfm.set_tx_power(17, true).expect("set_tx_power");                // Set TX power, (power_dbm, use_pa_boost) → Result

    // --- Ping-pong exchange loop ---
    // Send an incrementing counter, then wait up to 2s for an echo back.
    // print round-trip time, RSSI, and SNR for each successful exchange.
    let mut counter: u16 = 0;
    let mut successes: u32 = 0;
    let mut failures: u32 = 0;

    for _ in 0..10 {
        let tx_payload = [(counter >> 8) as u8, counter as u8];
        rfm.send(&tx_payload).expect("send");                         // Transmit packet, (data: &[u8]) → Result

        let rx = rfm.receive(2000).expect("receive");                 // Receive packet, (timeout_ms) → Result<Option<Vec<u8>>>

        if let Some(_pkt) = rx {
            let rssi = rfm.last_packet_rssi().expect("rssi");        // Read packet RSSI, () → Result<f32>
            let snr = rfm.last_packet_snr().expect("snr");            // Read packet SNR, () → Result<f32>
            println!("seq={} rssi={:.1} snr={:.1}", counter, rssi, snr);
            successes += 1;
        } else {
            println!("seq={} timeout", counter);
            failures += 1;
        }

        counter = counter.wrapping_add(1);
        std::thread::sleep(std::time::Duration::from_millis(100));
    }

    println!("=== {} success, {} lost ===", successes, failures);
}