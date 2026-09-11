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

    let mut radio = Rfm95Full::new(device, 868_000_000).expect("init RFM95");    // Create RFM95W full driver, (spi, frequency_hz=868e6) → Result

    let ver = radio.version().unwrap_or(0xFF);                                  // Read silicon version, () → Result<u8>
                                                                                  // expect 0x12 (SX1276)
    println!("version: 0x{:02X}", ver);

    radio.configure(7, 125.0, 5, true).expect("configure");                     // Configure LoRa modem, (sf=6–12, bandwidth_khz=7.8–500, coding_rate=5–8, crc=true) → Result<()>
    radio.set_tx_power(17, true).expect("tx_power");                            // Set TX power, (power_dbm=2–20, use_pa_boost=true) → Result<()>
    radio.set_frequency(868_000_000).expect("freq");                            // Change carrier frequency, (frequency_hz=862e6–1020e6) → Result<()>
    radio.standby().expect("standby");                                          // Enter STDBY mode, () → Result<()>
    radio.reset().expect("reset");                                              // Re-run init sequence (software reset), () → Result<()>

    radio.send(b"hello world").expect("send");                                  // Send packet, (data=&[u8] ≤255 B) → Result<()>
    let _ = radio.receive(2000, false).expect("receive");                       // Receive single packet, (timeout_ms=2000, use_interrupt=false) → Result<Option<[u8;256]>>

    radio.receive_continuous().expect("rx_cont");                               // Enter continuous RX, () → Result<()>
    let _ = radio.read_packet().expect("read_packet");                         // Read one packet in continuous RX, () → Result<Option<[u8;256]>>
    radio.stop_receive().expect("stop");                                        // Return to STDBY from RX_CONT, () → Result<()>

    radio.sleep().expect("sleep");                                             // Enter SLEEP mode, () → Result<()>

    let mut delay = Delay::new();
    delay.delay_ms(250);
    loop {}
}
