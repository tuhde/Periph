//! Demo for the ST25DV — NFC tag with host mailbox (NDEF, areas, password, GPO events, mailbox).

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::rfid::{St25dvFull, GPO_FIELD_CHANGE, SOURCE_FIELD_FALLING, SOURCE_FIELD_RISING, SOURCE_RF_WRITE};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x53);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create ST25DV driver, (i2c, delay, user_addr=0x53, system_addr=0x57) → Self
    // the chip answers on two addresses on the same bus
    let mut chip = St25dvFull::new(dev, Delay, addr, 0x57);

    // Read MEM_SIZE, BLK_SIZE, IC_REF, () → Result<()>
    chip.init().expect("init");

    // --- Identify the tag ---
    // IC_REF tells the 04K apart from the 16K/64K; the size comes from MEM_SIZE.
    // Get IC reference, () → u8 ; Read IC revision, () → Result<u8>
    println!("IC_REF 0x{:02X}, revision {}", chip.ic_reference(), chip.ic_revision().expect("ic_revision"));
    // Read unique ID, () → Result<[u8; 8]> ; Get user memory size, () → u32 bytes
    println!("UID {:02X?} - {} bytes", chip.uid().expect("uid"), chip.memory_size());

    // --- NDEF text record a phone can read ---
    // Record header D1 (MB/ME/SR, well-known), type 'T', status 02 (language "en").
    let text = b"Hello from Periph";
    let mut record = [0u8; 7 + 17];
    record[..7].copy_from_slice(&[0xD1, 0x01, (3 + text.len()) as u8, 0x54, 0x02, b'e', b'n']);
    record[7..].copy_from_slice(text);
    // Write NDEF message, (message) → Result<()>
    chip.write_ndef(&record).expect("write_ndef");
    let mut buf = [0u8; 256];
    // Read NDEF message, (buf) → Result<usize> bytes
    let n = chip.read_ndef(&mut buf).expect("read_ndef");
    println!("NDEF read back: {}", &buf[..n] == &record[..]);

    // --- Raw bytes across a 256-byte page boundary ---
    // The driver splits the write into 16-byte-aligned pieces and ACK-polls each.
    let mut saved = [0u8; 32];
    // Read user memory, (address, buf) → Result<()>
    chip.read(0x00F8, &mut saved).expect("read");
    let mut pattern = [0u8; 32];
    for (i, b) in pattern.iter_mut().enumerate() {
        *b = 0x40 + i as u8;
    }
    // Write user memory, (address, data) → Result<()>
    chip.write(0x00F8, &pattern).expect("write");
    // Wait for the write cycle, () → Result<()>
    chip.wait_write_complete().expect("wait_write_complete");
    let mut back = [0u8; 32];
    // Read user memory, (address, buf) → Result<()>
    chip.read(0x00F8, &mut back).expect("read");
    println!("Page-crossing write ok: {}", back == pattern);
    // Write user memory, (address, data) → Result<()>
    chip.write(0x00F8, &saved).expect("write");

    // --- Password session and two memory areas ---
    // Area 1 = first 1 KiB (stays open), area 2 = the rest: RF writes need RF
    // password 1, I2C writes need the I2C session. The factory I2C password is 0.
    let pwd = [0u8; 8];
    // Query I²C security session, () → Result<bool>
    println!("Session before: {}", chip.session_open().expect("session_open"));
    // Present I²C password, (password [u8; 8]) → Result<bool>
    println!("Password accepted: {}", chip.present_password(&pwd).expect("present_password"));
    let end1 = (core::cmp::min(chip.memory_size(), 1024) / 32 - 1) as u8;
    let last = (chip.memory_size() / 32 - 1) as u8;
    // Set end of area 1-3, (area 1–3, end_block 0–255) → Result<()>
    chip.set_area_end(1, end1).expect("set_area_end");
    // Set end of area 1-3, (area 1–3, end_block 0–255) → Result<()>
    chip.set_area_end(2, last).expect("set_area_end");
    // Set end of area 1-3, (area 1–3, end_block 0–255) → Result<()>
    chip.set_area_end(3, last).expect("set_area_end");
    // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → Result<()>
    chip.set_area_protection(2, 0x05, 0x01).expect("set_area_protection");
    // Get area protection, (area 1–4) → Result<(u8, u8)>
    println!("Area 2 protection (rf, i2c): {:?}", chip.get_area_protection(2).expect("get_area_protection"));

    // --- Change and restore the I2C password ---
    let new_pwd = [0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0];
    // Change I²C password, (password [u8; 8]) → Result<()>
    chip.write_password(&new_pwd).expect("write_password");
    // Present I²C password, (password [u8; 8]) → Result<bool>
    println!("New password accepted: {}", chip.present_password(&new_pwd).expect("present_password"));
    // Change I²C password, (password [u8; 8]) → Result<()>
    chip.write_password(&pwd).expect("write_password");
    // Present I²C password, (password [u8; 8]) → Result<bool>
    println!("Factory password restored: {}", chip.present_password(&pwd).expect("present_password"));

    // --- GPO events while a phone approaches ---
    // Field change and RF write events pulse GPO. The Rust driver has no
    // subscription API: this loop polls IT_STS_Dyn. Hold a phone to the antenna now.
    // Set GPO pulse duration, (value 0–7) → Result<()>
    chip.set_interrupt_pulse(3).expect("set_interrupt_pulse");
    // Write static GPO register, (sources, enable) → Result<()>
    chip.set_gpo(GPO_FIELD_CHANGE, true).expect("set_gpo");
    // Enable interrupt sources, (source) → Result<()>
    chip.enable_interrupt(SOURCE_FIELD_RISING | SOURCE_RF_WRITE).expect("enable_interrupt");
    // Read static GPO register, () → Result<u8>
    println!("GPO register: 0x{:02X}", chip.get_gpo().expect("get_gpo"));
    for _ in 0..10 {
        // Read and clear IT_STS_Dyn, () → Result<u8>
        let s = chip.poll_interrupt().expect("poll_interrupt");
        if s & SOURCE_FIELD_RISING != 0 { println!("  GPO event: field on"); }
        if s & SOURCE_FIELD_FALLING != 0 { println!("  GPO event: field off"); }
        if s & SOURCE_RF_WRITE != 0 { println!("  GPO event: RF write"); }
        // Query RF field, () → Result<bool>
        println!("  field present: {}", chip.field_present().expect("field_present"));
        sleep_ms!(1000);
    }
    // Disable interrupt sources, (source) → Result<()>
    chip.disable_interrupt(SOURCE_RF_WRITE).expect("disable_interrupt");

    // --- Fast transfer mode: host <-> phone mailbox ---
    // The host puts a message; an RF reader (e.g. the ST25 app) reads it and can
    // answer. The watchdog releases an unread message after 2^(7-1) x 30 ms.
    // Enable fast transfer mode, () → Result<()>
    chip.enable_mailbox().expect("enable_mailbox");
    // Set mailbox watchdog, (value 0–7) → Result<()>
    chip.set_mailbox_watchdog(7).expect("set_mailbox_watchdog");
    // Put mailbox message, (data 1–256 bytes) → Result<()>
    chip.mailbox_write(b"hello reader").expect("mailbox_write");
    for _ in 0..10 {
        // Decode mailbox status, () → Result<MailboxStatus>
        let st = chip.mailbox_status().expect("mailbox_status");
        if st.rf_put {
            // Read mailbox message, (buf) → Result<usize> bytes
            let n = chip.mailbox_read(&mut buf).expect("mailbox_read");
            println!("Reader says: {:02X?}", &buf[..n]);
            break;
        }
        println!("  mailbox: {:?}", st);
        sleep_ms!(1000);
    }
    // Disable fast transfer mode, () → Result<()>
    chip.disable_mailbox().expect("disable_mailbox");

    // --- Energy harvesting and RF state ---
    // Control energy harvesting, (enabled, forced) → Result<()>
    chip.set_energy_harvesting(true, false).expect("set_energy_harvesting");
    sleep_ms!(1000);
    // Control energy harvesting, (enabled, forced) → Result<()>
    chip.set_energy_harvesting(false, false).expect("set_energy_harvesting");
    // Set RF interface state, (disable, sleep) → Result<()>
    chip.set_rf_state(false, true).expect("set_rf_state");
    println!("RF asleep: a phone no longer sees the tag");
    sleep_ms!(2000);
    // Set RF interface state, (disable, sleep) → Result<()>
    chip.set_rf_state(false, false).expect("set_rf_state");

    // --- Restore the delivery defaults ---
    // One area, no protection, field-change GPO only, mailbox off, CC unlocked.
    // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → Result<()>
    chip.set_area_protection(2, 0x00, 0x00).expect("set_area_protection");
    // Set end of area 1-3, (area 1–3, end_block 0–255) → Result<()>
    chip.set_area_end(1, last).expect("set_area_end");
    // Write static GPO register, (sources, enable) → Result<()>
    chip.set_gpo(GPO_FIELD_CHANGE, true).expect("set_gpo");
    // Switch GPO output at runtime, (enable) → Result<()>
    chip.set_gpo_runtime(true).expect("set_gpo_runtime");
    // Write system area, (address, data) → Result<()>
    chip.write_system(0x000D, &[0x00]).expect("write_system");
    let mut mb_mode = [0u8; 1];
    // Read system area, (address, buf) → Result<()>
    chip.read_system(0x000D, &mut mb_mode).expect("read_system");
    println!("MB_MODE: {}", mb_mode[0]);
    // Lock CC file blocks, (lock) → Result<()>
    chip.lock_ccfile(false).expect("lock_ccfile");
    println!("Done.");
}
