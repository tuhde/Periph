// MFRC522 complete example on Linux host (SPI via spidev).

use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::rfid::{Mfrc522Full, RX_GAIN_38_DB, KEY_A};
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

    let mut mfrc = Mfrc522Full::new(device).expect("init MFRC522");              // Create MFRC522 driver, (spi) → Result

    let (chip, ver) = mfrc.version().expect("version");                        // Read version register, () → Result<(chipType, version)>
                                                                                // for MFRC522 chipType=0x09, version=1 (v1.0) or 2 (v2.0)
    println!("MFRC522 chip=0x{:X} version={}", chip, ver);

    let ok = mfrc.self_test().expect("self_test");                              // Run digital self test, () → Result<bool>
                                                                                // compares 64 FIFO bytes against the version-specific reference
    println!("self_test: {}", if ok { "PASS" } else { "FAIL" });

    mfrc.antenna_on().expect("antenna_on");                                     // Enable antenna driver (TX1+TX2), () → Result<()>
    mfrc.set_antenna_gain(RX_GAIN_38_DB).expect("set_antenna_gain");           // Set receiver gain, (dB=18/23/33/38/43/48) → Result<()>
                                                                                // 38 dB gives better read range on most antennas
    println!("current gain: 0x{:X}", mfrc.antenna_gain().unwrap_or(0));        // Read receiver gain, () → Result<u8>

    mfrc.reset().expect("reset");                                               // Soft reset and reinitialise, () → Result<()>
                                                                                // re-runs the full initialization sequence

    if let Some((uid, len)) = mfrc.select_card().expect("select_card") {        // Anticollision/Select (leaves card active), () → Result<Option<(uid, len)>>
        let uid_hex: String = uid[..len].iter().map(|b| format!("{:02X}", b)).collect();
        println!("UID: {}", uid_hex);
        let factory_key = [0xFFu8; 6];                                         // well-known default key — see spec
        if mfrc.authenticate(4, KEY_A, &factory_key, <&[u8; 4]>::try_from(&uid[..4]).unwrap()).expect("authenticate") { // Run MFAuthent, (block, keyType, key=6 B, uid=4 B) → Result<bool>
            if let Some(block) = mfrc.read_block(4).expect("read_block") {     // Read 16-byte block, (blockAddress) → Result<Option<[u8; 16]>>
                                                                                // requires successful authenticate for the containing sector
                println!("block 4: {:02X?}", &block[..]);
            }
            mfrc.decrement_value(4, 1).expect("decrement_value");              // Decrement value block, (block, delta=u32) → Result<bool>
                                                                                // runs Decrement + Transfer to the same block
            mfrc.stop_crypto().expect("stop_crypto");                          // Clear MFCrypto1On, () → Result<()>
                                                                                // required before authenticating a different sector
        }
        mfrc.halt_card().expect("halt_card");                                   // Send HLTA, () → Result<()>
    }
}
