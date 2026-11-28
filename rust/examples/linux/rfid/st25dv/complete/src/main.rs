//! Complete example for the ST25DV — every Full-driver method once.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::rfid::{St25dvFull, GPO_FIELD_CHANGE, SOURCE_FIELD_RISING, SOURCE_RF_WRITE};

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
    // derives the user memory size from the system area
    chip.init().expect("init");
    // Get user memory size, () → u32 bytes
    // (MEM_SIZE + 1) × (BLK_SIZE + 1)
    println!("Size: {} bytes", chip.memory_size());
    // Get IC reference, () → u8
    // 0x24 = ST25DV04K, 0x26 = ST25DV16K/64K
    println!("IC_REF: 0x{:02X}", chip.ic_reference());
    // Read unique ID, () → Result<[u8; 8]>
    // most significant byte first, starts with E0 02
    println!("UID: {:02X?}", chip.uid().expect("uid"));
    // Read IC revision, () → Result<u8>
    // IC_REV register 0x0020
    println!("IC_REV: {}", chip.ic_revision().expect("ic_revision"));

    let mut data = [0u8; 40];
    for (i, b) in data.iter_mut().enumerate() {
        *b = i as u8;
    }
    // Write user memory, (address, data) → Result<()>
    // split into 16-byte-aligned writes, ACK-polled each
    chip.write(0x0100, &data).expect("write");
    // Read user memory, (address, buf) → Result<()>
    // 16-byte-aligned reads, retried while an RF reader holds the bus
    chip.read(0x0100, &mut data).expect("read");
    println!("Data: {:02X?}", data);
    // Wait for the write cycle, () → Result<()>
    // ACK polling on 0x53
    chip.wait_write_complete().expect("wait_write_complete");

    let mut it_time = [0u8; 1];
    // Read system area, (address, buf) → Result<()>
    // raw access to the static registers at 0x57
    chip.read_system(0x0001, &mut it_time).expect("read_system");
    println!("IT_TIME: {}", it_time[0]);
    // Query I²C security session, () → Result<bool>
    // I2C_SSO_Dyn bit 0
    println!("Session: {}", chip.session_open().expect("session_open"));
    // Present I²C password, (password [u8; 8]) → Result<bool>
    // opens the session needed for system-area writes
    println!("Password OK: {}", chip.present_password(&[0u8; 8]).expect("present_password"));
    // Change I²C password, (password [u8; 8]) → Result<()>
    // here rewritten with the factory value
    chip.write_password(&[0u8; 8]).expect("write_password");
    // Write system area, (address, data) → Result<()>
    // IT_TIME back to its reset value, ACK-polled
    chip.write_system(0x0001, &[0x03]).expect("write_system");

    // Set end of area 1-3, (area 1–3, end_block 0–255) → Result<()>
    // area n ends at byte end_block × 32 + 31
    chip.set_area_end(1, (chip.memory_size() / 32 - 1) as u8).expect("set_area_end");
    // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → Result<()>
    // RFAnSS value and the area's I2CSS bits
    chip.set_area_protection(1, 0x00, 0x00).expect("set_area_protection");
    // Get area protection, (area 1–4) → Result<(u8, u8)>
    // (RFAnSS & 0x0F, I2CSS level)
    println!("Area 1 protection: {:?}", chip.get_area_protection(1).expect("get_area_protection"));

    // Write static GPO register, (sources, enable) → Result<()>
    // the chip copies it into GPO_CTRL_Dyn
    chip.set_gpo(GPO_FIELD_CHANGE, true).expect("set_gpo");
    // Read static GPO register, () → Result<u8>
    // GPO_* bit mask
    println!("GPO: 0x{:02X}", chip.get_gpo().expect("get_gpo"));
    // Switch GPO output at runtime, (enable) → Result<()>
    // GPO_CTRL_Dyn.GPO_EN, no session needed
    chip.set_gpo_runtime(true).expect("set_gpo_runtime");
    // Set GPO pulse duration, (value 0–7) → Result<()>
    // 301 µs − value × 37.65 µs
    chip.set_interrupt_pulse(3).expect("set_interrupt_pulse");

    // Query RF field, () → Result<bool>
    // EH_CTRL_Dyn.FIELD_ON
    println!("Field present: {}", chip.field_present().expect("field_present"));
    // Control energy harvesting, (enabled, forced) → Result<()>
    // EH_EN now, EH_MODE written only when it changes
    chip.set_energy_harvesting(false, false).expect("set_energy_harvesting");
    // Set RF interface state, (disable, sleep) → Result<()>
    // runtime RF_MNGT_Dyn
    chip.set_rf_state(false, false).expect("set_rf_state");

    // Enable fast transfer mode, () → Result<()>
    // sets MB_MODE if needed, then MB_CTRL_Dyn.MB_EN
    chip.enable_mailbox().expect("enable_mailbox");
    // Set mailbox watchdog, (value 0–7) → Result<()>
    // 2^(value−1) × 30 ms, 0 disables it
    chip.set_mailbox_watchdog(0).expect("set_mailbox_watchdog");
    // Put mailbox message, (data 1–256 bytes) → Result<()>
    // single write starting at 0x2008
    chip.mailbox_write(b"ping").expect("mailbox_write");
    // Decode mailbox status, () → Result<MailboxStatus>
    // enabled, host_put, rf_put, host_miss, rf_miss, …
    println!("Mailbox: {:?}", chip.mailbox_status().expect("mailbox_status"));
    let mut buf = [0u8; 256];
    // Read mailbox message, (buf) → Result<usize> bytes
    // MB_LEN_Dyn + 1 bytes, 0 if no message
    let n = chip.mailbox_read(&mut buf).expect("mailbox_read");
    println!("Message: {:02X?}", &buf[..n]);
    // Disable fast transfer mode, () → Result<()>
    // clears MB_EN, which also drops the message
    chip.disable_mailbox().expect("disable_mailbox");

    // Write NDEF message, (message) → Result<()>
    // capability container + TLV 03 <len> … FE
    chip.write_ndef(&[0xD1, 0x01, 0x05, 0x54, 0x02, 0x65, 0x6E, 0x48, 0x69, 0x21]).expect("write_ndef");
    // Read NDEF message, (buf) → Result<usize> bytes
    // parses the CC and the first NDEF TLV
    let n = chip.read_ndef(&mut buf).expect("read_ndef");
    println!("NDEF: {:02X?}", &buf[..n]);
    // Lock CC file blocks, (lock) → Result<()>
    // false keeps blocks 0/1 RF-writable
    chip.lock_ccfile(false).expect("lock_ccfile");

    // Enable interrupt sources, (source) → Result<()>
    // static GPO register bits plus GPO_EN
    chip.enable_interrupt(SOURCE_FIELD_RISING | SOURCE_RF_WRITE).expect("enable_interrupt");
    sleep_ms!(100);
    // Read and clear IT_STS_Dyn, () → Result<u8>
    // SOURCE_* mask, 0 = none; wire into a GPO edge handler or a polling loop
    println!("Pending: 0x{:02X}", chip.poll_interrupt().expect("poll_interrupt"));
    // Disable interrupt sources, (source) → Result<()>
    // clears the matching GPO enable bits
    chip.disable_interrupt(SOURCE_RF_WRITE).expect("disable_interrupt");
}
