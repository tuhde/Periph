// MFRC522 minimal example on Linux host (SPI via spidev).
//
// Wiring: MFRC522 SCK/MISO/MOSI on the Raspberry Pi SPI pins, NSS on the
// chosen CS line (default: /dev/spidev0.0 → CE0, GPIO 8). Adjust the
// device path via SPI_DEV if your board uses a different bus/CS.

use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::rfid::Mfrc522Minimal;
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
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus, NullCs).expect("spi device");

    let mut mfrc = Mfrc522Minimal::new(device).expect("init MFRC522");       // Create MFRC522 driver, (spi) → Result

    for _ in 0..10 {
        let present = mfrc.is_card_present().expect("is_card_present");      // Detect card in field, () → Result<bool>
        let uid = mfrc.read_uid().expect("read_uid");                        // Read card UID (REQA → anticollision → HLTA), () → Result<Option<(uid, len)>>
        let uid_hex = uid.as_ref().map(|(u, l)| u[..*l].iter().map(|b| format!("{:02X}", b)).collect::<String>()).unwrap_or_default();
        println!("present={} uid={}", present, uid_hex);
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
}
