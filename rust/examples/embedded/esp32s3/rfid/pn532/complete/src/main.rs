//! Complete example for the PN532 — every API method of the Full driver. (ESP32-S3: SDA GPIO1, SCL GPIO2).
#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
#[allow(unused_imports)]
use esp_println::{print, println};
use periph::chips::rfid::pn532;
use periph::chips::rfid::Pn532Full;

esp_app_desc!();

const ADDR: u8 = 0x24;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let bus = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    #[allow(unused_mut)]
    let mut delay = Delay::new();
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { delay.delay_ms($n) }; }
    let addr = ADDR;

    // Create PN532 driver, (i2c, addr=0x24) → Pn532Full
    // Stores the bus only; no bus traffic yet. I0 low and I1 high select the I²C interface.
    let mut chip = Pn532Full::new(bus, addr);
    // Wake, check firmware, SAM normal mode, finite retries, (delay) → Result<()>
    // GetFirmwareVersion (IC 0x32), SAMConfiguration normal mode, MxRtyPassiveActivation = 2.
    chip.init(&mut delay).expect("init");

    // GetFirmwareVersion, (delay) → Result<FirmwareVersion {ic, version, revision, support, iso14443a, iso14443b, iso18092}>
    // IC 0x32, firmware 1.6 on current parts; the support bits list the protocols.
    println!("{:?}", chip.firmware_version(&mut delay).expect("firmware"));
    // GetGeneralStatus, (delay) → Result<GeneralStatus {error, field, targets, sam_status}>
    // Last error code, external field detected, active targets, SAM status.
    println!("{:?}", chip.general_status(&mut delay).expect("status"));
    // Diagnose, (delay, test=0x00) → Result<bool>
    // Communication line test: the chip echoes an 8-byte pattern.
    println!("self test {}", chip.self_test(&mut delay, 0x00).expect("self test"));
    // Diagnose, (delay, test=0x01) → Result<bool>
    // ROM checksum test.
    println!("ROM test {}", chip.self_test(&mut delay, 0x01).expect("rom test"));

    // Detect a type A card, (delay, timeout_ms=1000) → Result<Option<Vec<u8, 10>>>
    // InListPassiveTarget at 106 kbps type A, then InRelease.
    println!("UID {:?}", chip.read_uid(&mut delay, 1000).expect("read_uid"));

    // InListPassiveTarget, (delay, baud=BAUD_106_A, max_targets=1, initiator_data=&[], timeout_ms=1000) → Result<Vec<Target, 2>>
    // Keeps the target active; target.tg is the logical number for the next commands.
    let targets = chip.list_passive_targets(&mut delay, pn532::BAUD_106_A, 1, &[], 1000).expect("list");
    if let Some(t) = targets.first() {
        println!("{:?}", t);
        // InDeselect, (delay, target=0) → Result<()>
        // Puts the card to sleep but keeps its information in the PN532.
        chip.deselect(&mut delay, t.tg).expect("deselect");
        // InSelect, (delay, target=0) → Result<()>
        // Wakes and reselects the deselected card.
        chip.select(&mut delay, t.tg).expect("select");
        if t.uid.len() == 7 {
            // NTAG READ, (delay, target, page) → Result<[u8; 16]>
            // Returns pages 4..7 (16 bytes).
            println!("{:?}", chip.ntag_read_page(&mut delay, t.tg, 4));
            // NTAG WRITE, (delay, target, page, data &[u8; 4]) → Result<()>
            // Writes one 4-byte user page.
            println!("{:?}", chip.ntag_write_page(&mut delay, t.tg, 4, &[1, 2, 3, 4]));
        } else {
            // MIFARE auth, (delay, target, block, key &[u8; 6], uid, key_b=false) → Result<()>
            // Key A authentication of sector 1 with the transport key.
            println!("{:?}", chip.mifare_authenticate(&mut delay, t.tg, 4, &[0xFF; 6], &t.uid, false));
            // MIFARE READ, (delay, target, block) → Result<[u8; 16]>
            // Reads block 4.
            if let Ok(block) = chip.mifare_read_block(&mut delay, t.tg, 4) {
                // MIFARE WRITE, (delay, target, block, data &[u8; 16]) → Result<()>
                // Writes the same 16 bytes back.
                println!("{:?}", chip.mifare_write_block(&mut delay, t.tg, 4, &block));
            }
        }
        // InDataExchange, (delay, target, data) → Result<Vec<u8, 64>>
        // Raw READ of block/page 0 with PN532 framing and CRC.
        println!("{:?}", chip.data_exchange(&mut delay, t.tg, &[0x30, 0x00]));
        // InRelease, (delay, target=0) → Result<()>
        // Ends the session with the card.
        chip.release(&mut delay, t.tg).expect("release");
    }

    // RFConfiguration item 1, (delay, on, auto_rfca=false) → Result<()>
    // RF field off; a card in the field loses power.
    chip.set_rf_field(&mut delay, false, false).expect("rf off");
    // RFConfiguration item 1, (delay, on, auto_rfca=false) → Result<()>
    // RF field back on.
    chip.set_rf_field(&mut delay, true, false).expect("rf on");
    // RFConfiguration item 5, (delay, passive_activation=2, atr=0xFF, psl=1) → Result<()>
    // 0xFF would retry forever.
    chip.set_max_retries(&mut delay, 2, 0xFF, 1).expect("retries");
    // ReadRegister, (delay, address) → Result<u8>
    // 0x6302 is CIU_TxMode in the PN532's SFR space.
    let tx_mode = chip.read_register(&mut delay, 0x6302).expect("read register");
    // WriteRegister, (delay, address, value) → Result<()>
    // Writes the value back unchanged.
    chip.write_register(&mut delay, 0x6302, tx_mode).expect("write register");
    // Raw command, (delay, command, data, timeout_ms=1000) → Result<Vec<u8, 64>>
    // GetFirmwareVersion through the raw frame interface.
    println!("{:?}", chip.send_command(&mut delay, 0x02, &[], 1000).expect("raw"));
    // InCommunicateThru, (delay, data) → Result<Vec<u8, 64>>
    // Raw RF exchange with the current target; fails without one.
    println!("{:?}", chip.communicate_thru(&mut delay, &[0x26]));

    // Command without waiting, (delay, command, data) → Result<()>
    // Writes the frame and reads the ACK only.
    chip.start_command(&mut delay, 0x02, &[]).expect("start");
    // Wait for a frame, (delay, timeout_ms) → Result<bool>
    // Polls the status byte every 1 ms.
    println!("ready {}", chip.wait_ready(&mut delay, 1000).expect("wait"));
    // Frame ready?, () → Result<bool>
    // A single status-byte read; wire IRQ into an ISR or loop and call this.
    println!("poll {}", chip.poll_interrupt().expect("poll"));
    // Read the pending response, (delay, command, timeout_ms=1000) → Result<Vec<u8, 64>>
    // Header, NACK, full frame, checksums.
    println!("{:?}", chip.read_response(&mut delay, 0x02, 1000).expect("response"));

    // PowerDown, (delay, wakeup_sources=0x20) → Result<()>
    // Sleeps until the next I²C access; the driver retries the wake-up write.
    chip.power_down(&mut delay, 0x20).expect("power down");

    loop {
        sleep_ms!(1000);
    }
}
