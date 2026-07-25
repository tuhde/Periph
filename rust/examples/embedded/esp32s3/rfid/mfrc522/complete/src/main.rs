#![no_std]
#![no_main]

use embedded_hal_bus::spi::ExclusiveDevice;
use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::gpio::Output;
use esp_hal::spi::master::{Config, Spi};
use esp_println::println;
use periph::chips::rfid::{Mfrc522Full, RX_GAIN_38_DB, KEY_A};

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
    let mut delay = Delay::new();


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
    loop {}
}
