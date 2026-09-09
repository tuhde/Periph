/* RFM95W demo — two-node round-trip link test.
 *
 * Hardware: two RFM95W modules wired back-to-back (or two boards running
 * the same code). Both configured for 868 MHz, SF=7, BW=125 kHz, CR 4/5.
 *
 * Runs 10 TX/RX iterations: transmits an incrementing 4-byte counter, then
 * immediately waits up to 1 s for the peer to echo it back. Prints the
 * round-trip time and per-packet RSSI/SNR on success, then reports the
 * total packet loss.
 */
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

    let mut radio = Rfm95Full::new(device, 868_000_000).expect("init RFM95");    // Create RFM95W driver, (spi, frequency_hz=868e6) → Result

    // --- Configure for short-range link test ---
    // SF7 / 125 kHz / 4/5 keeps airtime low so the round-trip fits in a 1 s window;
    // +17 dBm on PA_BOOST gives enough link margin for desk-top loop-back.
    radio.inner.inner.configure(7, 125.0, 5).expect("configure");                  // Configure LoRa modem, (sf=7, bandwidth_khz=125.0, coding_rate=5) → Result<()>
    radio.inner.inner.set_tx_power(17, true).expect("tx_power");                   // Set TX power, (power_dbm=17, use_pa_boost=true) → Result<()>

    let mut loss = 0u32;
    let total = 10;
    for n in 0..total {
        let tx = (n as u32).to_be_bytes();
        let t0 = std::time::SystemTime::now();
        radio.inner.inner.send(&tx).expect("send");                                // Send packet, (data=&[u8] ≤255 B) → Result<()>

        let rx = radio.inner.inner.receive(1000).expect("receive");                // Receive single packet, (timeout_ms=1000) → Result<Option<[u8;256]>>
        let t1 = std::time::SystemTime::now();

        let rtt = t1.duration_since(t0).map(|d| d.as_millis()).unwrap_or(0);
        if let Some(buf) = rx.as_ref() {
            if &buf[..4] == tx {
                let rssi = radio.inner.inner.last_packet_rssi().unwrap_or(0.0);   // Last packet RSSI, () → Result<f32> dBm
                let snr  = radio.inner.inner.last_packet_snr().unwrap_or(0.0);    // Last packet SNR, () → Result<f32> dB
                println!("[{}] echo rtt={} ms  rssi={:.1}  snr={:.1}", n, rtt, rssi, snr);
            } else {
                loss += 1;
                println!("[{}] no echo", n);
            }
        } else {
            loss += 1;
            println!("[{}] no echo", n);
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }
    println!("done — {}/{} successful, {} lost", total - loss, total, loss);
}
