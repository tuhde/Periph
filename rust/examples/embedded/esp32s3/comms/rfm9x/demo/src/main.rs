/* RFM95W demo — two-node round-trip link test (ESP32-S3).
 *
 * Hardware: two RFM95W modules wired back-to-back (or two boards running
 * the same code). Both configured for 868 MHz, SF=7, BW=125 kHz, CR 4/5.
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
use periph::chips::comms::Rfm95Full;

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

    let mut radio = Rfm95Full::new(device, 868_000_000).expect("init RFM95");    // Create RFM95W driver, (spi, frequency_hz=868e6) → Result

    // --- Configure for short-range link test ---
    // SF7 / 125 kHz / 4/5 keeps airtime low so the round-trip fits in a 1 s window;
    // +17 dBm on PA_BOOST gives enough link margin for desk-top loop-back.
    radio.inner.inner.configure(7, 125.0, 5).expect("configure");                  // Configure LoRa modem, (sf=7, bandwidth_khz=125.0, coding_rate=5) → Result<()>
    radio.inner.inner.set_tx_power(17, true).expect("tx_power");                   // Set TX power, (power_dbm=17, use_pa_boost=true) → Result<()>

    let mut loss = 0u32;
    let total = 10;
    let mut delay = Delay::new();
    for n in 0..total {
        let tx = (n as u32).to_be_bytes();
        radio.inner.inner.send(&tx).expect("send");                                // Send packet, (data=&[u8] ≤255 B) → Result<()>

        let rx = radio.inner.inner.receive(1000).expect("receive");                // Receive single packet, (timeout_ms=1000) → Result<Option<[u8;256]>>

        if let Some(buf) = rx.as_ref() {
            if &buf[..4] == tx {
                let rssi = radio.inner.inner.last_packet_rssi().unwrap_or(0.0);   // Last packet RSSI, () → Result<f32> dBm
                let snr  = radio.inner.inner.last_packet_snr().unwrap_or(0.0);    // Last packet SNR, () → Result<f32> dB
                println!("[{}] echo  rssi={:.1}  snr={:.1}", n, rssi, snr);
            } else {
                loss += 1;
                println!("[{}] no echo", n);
            }
        } else {
            loss += 1;
            println!("[{}] no echo", n);
        }
        delay.delay_ms(200);
    }
    println!("done — {}/{} successful, {} lost", total - loss, total, loss);

    loop {}
}
