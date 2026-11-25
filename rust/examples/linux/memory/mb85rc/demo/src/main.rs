use linux_embedded_hal::I2cdev;
use periph::chips::memory::Mb85rcFull;
use std::time::Instant;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Mb85rcFull::new(dev, addr);                                                               // Create MB85RC driver, (i2c, addr=0x50) → Self

    // --- Persistent boot counter ---
    // A 4-byte big-endian counter at 0x0000 survives power loss. FRAM has no
    // write cycle, so the increment is committed the moment the write ends.
    let mut raw = [0u8; 4];
    chip.read(0x0000, &mut raw).expect("read");                                               // Sequential read, (address, buf) → Result<()>
    let counter = u32::from_be_bytes(raw).wrapping_add(1);
    chip.write(0x0000, &counter.to_be_bytes()).expect("write");                               // Sequential write, (address, data) → Result<()>
    println!("Boot count: {}", counter);

    // --- Ring-buffer logger, back-to-back with no delays ---
    // 1,000 records of 16 bytes (index + counter + padding) are written one
    // after the other without any pause — an EEPROM would need ACK polling
    // after every page, FRAM does not.
    const LOG_BASE: u16 = 0x0100;
    const RECORD: usize = 16;
    const COUNT: u32 = 1000;
    const RING: u32 = 200;
    let t0 = Instant::now();
    for i in 0..COUNT {
        let mut rec = [0u8; RECORD];
        rec[..4].copy_from_slice(&i.to_be_bytes());
        rec[4..8].copy_from_slice(&counter.to_be_bytes());
        chip.write(LOG_BASE + (i % RING) as u16 * RECORD as u16, &rec).expect("write");           // Sequential write, (address, data) → Result<()>
    }
    let secs = t0.elapsed().as_secs_f64();
    println!("Logged {} bytes in {:.3} s = {} B/s", COUNT as usize * RECORD, secs, (COUNT as f64 * RECORD as f64 / secs) as u64);

    // --- Verify the last five records ---
    // Read back the newest records from the ring and check their contents.
    let mut ok = true;
    for i in (COUNT - 5)..COUNT {
        let mut rec = [0u8; RECORD];
        chip.read(LOG_BASE + (i % RING) as u16 * RECORD as u16, &mut rec).expect("read");         // Sequential read, (address, buf) → Result<()>
        if rec[..4] != i.to_be_bytes() || rec[4..8] != counter.to_be_bytes() {
            ok = false;
        }
    }
    println!("Last 5 records verified: {}", ok);

    // --- Clear the log area ---
    // fill() zeroes the whole ring in chunked transactions.
    chip.fill(LOG_BASE, RING as usize * RECORD, 0).expect("fill");                            // Fill a range, (address, length, value) → Result<()>
    println!("Log cleared; array size: {} bytes", chip.size());  // Get array size, () → usize

    // --- Device ID (needs the reserved address 0x7C) ---
    // Some hosts (Linux i2c-dev) refuse addresses 0x78-0x7F; skip then.
    match chip.read_device_id() {                                                                   // Read Device ID, () → Result<(u16, u8, u8)>
        Ok(id) => println!("Device ID: {:?}", id),
        Err(_) => println!("Device ID not available"),
    }
}
