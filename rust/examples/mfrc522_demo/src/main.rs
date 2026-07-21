// MFRC522 demo on Linux host (SPI via spidev) — prepaid-card credit counter.

use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::rfid::{Mfrc522Full, KEY_A};
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

    let mut mfrc = Mfrc522Full::new(device).expect("init MFRC522");

    // --- Prepaid-card credit counter ---
    // Simulates a transit-gate / vending-machine credit system using a MIFARE
    // Classic value block. The factory default key A (FF FF FF FF FF FF) is
    // used for the demo only — replace with a per-deployment secret in any
    // real access-control system.
    const CREDITS_BLOCK: u8 = 4;
    const INITIAL_CREDITS: u32 = 10;

    // --- Detect a card and select it for authenticated access ---
    let res = mfrc.select_card().expect("select_card");                        // Anticollision/Select only, () → Result<Option<(uid, len)>>
    if let Some((uid, len)) = res {
        // --- Authenticate with the well-known MIFARE factory default key A ---
        // In a real deployment this would be a per-card key stored securely
        // (e.g. diversified per card UID and held in an HSM or secure element).
        let factory_key = [0xFFu8; 6];
        let uid4: [u8; 4] = <&[u8; 4]>::try_from(&uid[..4.min(len)]).map(|s| *s).unwrap_or([0u8; 4]);
        if mfrc.authenticate(CREDITS_BLOCK, KEY_A, &factory_key, &uid4).expect("authenticate") { // MFAuthent, (block, key, uid) → Result<bool>
            // --- Read the current value block; initialise it if unprogrammed ---
            if let Some(block) = mfrc.read_block(CREDITS_BLOCK).expect("read_block") { // Read 16-byte block, (blockAddress) → Result<Option<[u8; 16]>>
                if block.iter().all(|&b| b == 0) {
                    let mut vb = [0u8; 16];
                    vb[0..4].copy_from_slice(&INITIAL_CREDITS.to_le_bytes());
                    let inv = !INITIAL_CREDITS;
                    vb[4..8].copy_from_slice(&inv.to_le_bytes());
                    vb[8..12].copy_from_slice(&INITIAL_CREDITS.to_le_bytes());
                    vb[12] = CREDITS_BLOCK;
                    vb[13] = !CREDITS_BLOCK;
                    vb[14] = CREDITS_BLOCK;
                    vb[15] = !CREDITS_BLOCK;
                    mfrc.write_block(CREDITS_BLOCK, &vb).expect("write_block");   // Write 16 bytes, (block, data=16 B) → Result<bool>
                    mfrc.restore_value(CREDITS_BLOCK).expect("restore_value");   // Restore + Transfer, (block) → Result<bool>
                                                                                   // normalises the value-block layout
                }
            }

            // --- "Spend" one credit; refuse if balance is zero ---
            if let Some(block) = mfrc.read_block(CREDITS_BLOCK).expect("read_block") { // Read current value, (block) → Result<Option<[u8; 16]>>
                let credits = u32::from_le_bytes([block[0], block[1], block[2], block[3]]);
                if credits == 0 {
                    println!("Access denied — no credits remaining");
                } else {
                    mfrc.decrement_value(CREDITS_BLOCK, 1).expect("decrement_value");  // Decrement + Transfer, (block, delta) → Result<bool>
                    if let Some(updated) = mfrc.read_block(CREDITS_BLOCK).expect("read_block") { // Read updated value, (block) → Result<Option<[u8; 16]>>
                        let new_balance = u32::from_le_bytes([updated[0], updated[1], updated[2], updated[3]]);
                        println!("spent 1 credit — remaining: {}", new_balance);
                    }
                }
            }
            mfrc.stop_crypto().expect("stop_crypto");                                    // Clear MFCrypto1On, () → Result<()>
        }
        mfrc.halt_card().expect("halt_card");                                          // Send HLTA, () → Result<()>
    } else {
        println!("no card in field");
    }
}
