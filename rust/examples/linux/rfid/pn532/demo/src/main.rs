//! Demo for the PN532 — tag reader and writer.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::rfid::pn532;
use periph::chips::rfid::Pn532Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x24);

    let bus = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create PN532 driver, (i2c, addr=0x24) → Pn532Full
    let mut chip = Pn532Full::new(bus, addr);
    // Wake, check firmware, SAM normal mode, finite retries, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Identity and health ---
    // IC 0x32 is the PN532; the support byte lists ISO 14443 A/B and ISO 18092.
    // The communication-line self test echoes a pattern through the firmware.
    // GetFirmwareVersion, (delay) → Result<FirmwareVersion>
    let fw = chip.firmware_version(&mut delay).expect("firmware");
    println!("PN532 IC 0x{:02X} firmware {}.{} support 0x{:02X}", fw.ic, fw.version, fw.revision, fw.support);
    // GetGeneralStatus, (delay) → Result<GeneralStatus>
    println!("{:?}", chip.general_status(&mut delay).expect("status"));
    // Diagnose, (delay, test=0x00) → Result<bool>
    println!("self test {}", chip.self_test(&mut delay, 0x00).expect("self test"));

    // --- Card polling: 15 s at 1 Hz ---
    // Present a card or tag. The finite retry count makes every poll return
    // after a few milliseconds when the field is empty.
    for i in 0..15 {
        // InListPassiveTarget, (delay, baud=BAUD_106_A, max_targets=1, ...) → Result<Vec<Target, 2>>
        let targets = chip.list_passive_targets(&mut delay, pn532::BAUD_106_A, 1, &[], 1000).expect("list");
        if let Some(t) = targets.first() {
            print!("{:2} s UID ", i);
            for b in t.uid.iter() {
                print!("{:02X}", b);
            }
            println!(" SENS_RES 0x{:04X} SEL_RES 0x{:02X}", t.sens_res, t.sel_res);
            // InRelease, (delay, target=0) → Result<()>
            chip.release(&mut delay, t.tg).expect("release");
        } else {
            println!("{:2} s no card", i);
        }
        sleep_ms!(1000);
    }

    // --- Tag access ---
    // SEL_RES 0x00 is an NTAG / MIFARE Ultralight: read page 4 and write it back.
    // SEL_RES 0x08 is a MIFARE Classic 1K: authenticate sector 1 with the transport
    // key FF FF FF FF FF FF, read block 4 and write it back. Writing sector trailers
    // can lock a sector forever.
    // InListPassiveTarget, (delay, baud=BAUD_106_A, ...) → Result<Vec<Target, 2>>
    let targets = chip.list_passive_targets(&mut delay, pn532::BAUD_106_A, 1, &[], 1000).expect("list");
    if let Some(t) = targets.first() {
        // InDeselect, (delay, target=0) → Result<()>
        chip.deselect(&mut delay, t.tg).expect("deselect");
        // InSelect, (delay, target=0) → Result<()>
        chip.select(&mut delay, t.tg).expect("select");
        if t.sel_res == 0x00 {
            // NTAG READ, (delay, target, page) → Result<[u8; 16]>
            if let Ok(page) = chip.ntag_read_page(&mut delay, t.tg, 4) {
                println!("pages 4..7 {:02X?}", page);
                // NTAG WRITE, (delay, target, page, data &[u8; 4]) → Result<()>
                let _ = chip.ntag_write_page(&mut delay, t.tg, 4, &[page[0], page[1], page[2], page[3]]);
            }
            // InDataExchange, (delay, target, data) → Result<Vec<u8, 64>>
            println!("raw read {:02X?}", chip.data_exchange(&mut delay, t.tg, &[0x30, 0x00]));
        } else if t.sel_res & 0x08 != 0 {
            // MIFARE auth, (delay, target, block, key &[u8; 6], uid, key_b=false) → Result<()>
            match chip.mifare_authenticate(&mut delay, t.tg, 4, &[0xFF; 6], &t.uid, false) {
                Ok(()) => {
                    // MIFARE READ, (delay, target, block) → Result<[u8; 16]>
                    if let Ok(block) = chip.mifare_read_block(&mut delay, t.tg, 4) {
                        println!("block 4 {:02X?}", block);
                        // MIFARE WRITE, (delay, target, block, data &[u8; 16]) → Result<()>
                        let _ = chip.mifare_write_block(&mut delay, t.tg, 4, &block);
                    }
                }
                Err(e) => println!("authentication failed: {:?}", e),
            }
        }
        // InRelease, (delay, target=0) → Result<()>
        let _ = chip.release(&mut delay, t.tg);
    } else {
        println!("no card for the tag access part");
    }

    // --- Raw RF and raw commands ---
    // InCommunicateThru sends bytes without PN532 protocol handling; with no
    // target selected it reports an error. send_command reaches every command.
    // InCommunicateThru, (delay, data) → Result<Vec<u8, 64>>
    println!("thru {:?}", chip.communicate_thru(&mut delay, &[0x30, 0x00]));
    // Raw command, (delay, command, data, timeout_ms=1000) → Result<Vec<u8, 64>>
    println!("raw GetFirmwareVersion {:02X?}", chip.send_command(&mut delay, 0x02, &[], 1000).expect("raw"));
    // ReadRegister, (delay, address) → Result<u8>
    let tx_mode = chip.read_register(&mut delay, 0x6302).expect("read register");
    println!("CIU_TxMode 0x{:02X}", tx_mode);
    // WriteRegister, (delay, address, value) → Result<()>
    chip.write_register(&mut delay, 0x6302, tx_mode).expect("write register");

    // --- Wake on card with poll_interrupt ---
    // Infinite activation retries make InListPassiveTarget wait for a card. The Rust
    // driver has no callback API: an ISR on IRQ (or this loop) checks poll_interrupt.
    // RFConfiguration item 5, (delay, passive_activation=2, atr=0xFF, psl=1) → Result<()>
    chip.set_max_retries(&mut delay, 0xFF, 0xFF, 1).expect("retries");
    // Command without waiting, (delay, command, data) → Result<()>
    chip.start_command(&mut delay, 0x4A, &[0x01, 0x00]).expect("start");
    println!("waiting 10 s for a card ...");
    let mut arrived = false;
    for _ in 0..100 {
        // Frame ready?, () → Result<bool>
        if chip.poll_interrupt().expect("poll") {
            arrived = true;
            break;
        }
        sleep_ms!(100);
    }
    // Wait for a frame, (delay, timeout_ms) → Result<bool>
    if arrived && chip.wait_ready(&mut delay, 10).expect("wait") {
        // Read the pending response, (delay, command, timeout_ms=1000) → Result<Vec<u8, 64>>
        let resp = chip.read_response(&mut delay, 0x4A, 1000).expect("response");
        if resp.len() >= 6 {
            println!("card arrived, UID {:02X?}", &resp[6..6 + resp[5] as usize]);
        }
        // InRelease, (delay, target=0) → Result<()>
        chip.release(&mut delay, 0).expect("release");
    } else {
        // Read the pending response, (delay, command, timeout_ms) → Result<Vec<u8, 64>>
        // A timeout aborts the command with an ACK frame.
        let _ = chip.read_response(&mut delay, 0x4A, 0);
        println!("no card arrived");
    }
    // RFConfiguration item 5, (delay, passive_activation=2, atr=0xFF, psl=1) → Result<()>
    chip.set_max_retries(&mut delay, 2, 0xFF, 1).expect("retries");
    // Detect a type A card, (delay, timeout_ms=1000) → Result<Option<Vec<u8, 10>>>
    println!("read_uid {:?}", chip.read_uid(&mut delay, 1000).expect("read_uid"));

    // --- RF field off and on, then power down ---
    // With the field off a card loses power; the reader draws less current.
    // RFConfiguration item 1, (delay, on, auto_rfca=false) → Result<()>
    chip.set_rf_field(&mut delay, false, false).expect("rf off");
    sleep_ms!(1000);
    // RFConfiguration item 1, (delay, on, auto_rfca=false) → Result<()>
    chip.set_rf_field(&mut delay, true, false).expect("rf on");
    // PowerDown, (delay, wakeup_sources=0x20) → Result<()>
    chip.power_down(&mut delay, 0x20).expect("power down");
    println!("powered down; the next I2C access wakes the chip");
}
