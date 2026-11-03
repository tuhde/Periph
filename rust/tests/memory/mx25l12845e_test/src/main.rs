use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::memory::{Mx25l12845eFull, Mx25l12845eOptions};
use periph::storage::BlockDevice;
use spidev::{SpiModeFlags, Spidev, SpidevOptions};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

// All erases and writes stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
const SCRATCH: u32 = 0xFF0000;

// Dummy CS: spidev manages CS# in the kernel.
struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn now_ms() -> u32 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_millis() as u32
}

fn delay_ms(ms: u32) {
    std::thread::sleep(Duration::from_millis(ms as u64));
}

fn options() -> Mx25l12845eOptions {
    Mx25l12845eOptions { now_ms: Some(now_ms), delay_ms: Some(delay_ms), auto_erase: false }
}

fn open_flash() -> ExclusiveDevice<SpidevBus, NullCs, linux_embedded_hal::Delay> {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", bus, dev)).expect("open spi");
    spi.configure(&SpidevOptions::new().max_speed_hz(8_000_000).mode(SpiModeFlags::SPI_MODE_0).build()).expect("configure spi");
    ExclusiveDevice::new(SpidevBus(spi), NullCs, linux_embedded_hal::Delay).expect("spi device")   // Create SPI device, (bus, cs, delay) → ExclusiveDevice
}

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let (mut passed, mut failed) = (0i32, 0i32);
    let mut flash = Mx25l12845eFull::new(open_flash(), options()).expect("init MX25L12845E");   // Create MX25L12845E driver and check the ID, (spi, options) → Result
    check_true!(true, "init (JEDEC C2 20 18)", passed, failed);
    check_true!(flash.jedec_id().unwrap() == [0xC2, 0x20, 0x18], "jedec_id", passed, failed);
    check_true!(flash.electronic_id().unwrap() == 0x17, "electronic_id (RES) = 17h", passed, failed);
    check_true!(flash.device_ids().unwrap() == (0xC2, 0x17), "device_ids (REMS) = C2 17", passed, failed);
    let sr = flash.read_status().unwrap();
    check_true!(sr & 0x03 == 0, "status: not busy, WEL clear", passed, failed);
    let bp = flash.protection().unwrap();
    println!("INFO status 0x{:02X}, BP={}", sr, bp);
    println!("INFO security 0x{:02X} (bit0 = OTP factory locked)", flash.read_security().unwrap());
    let esn = flash.serial_number().unwrap();
    println!("INFO ESN: {:02X?}", esn);
    check_true!(flash.serial_number().unwrap() == esn, "ESN stable", passed, failed);
    let mut sfdp = [0u8; 4];
    flash.read_sfdp(0, &mut sfdp).unwrap();
    check_true!(&sfdp == b"SFDP", "SFDP signature", passed, failed);
    if bp != 0 { println!("INFO protection was BP={}; clearing for the test", bp); flash.set_protection(0).unwrap(); }

    let mut back = vec![0u8; 4096];
    flash.erase_sector(SCRATCH).unwrap();
    flash.read_bytes(SCRATCH, &mut back).unwrap();
    check_true!(back.iter().all(|&b| b == 0xFF), "sector blank (all FF) after erase", passed, failed);

    let page: Vec<u8> = (0..256usize).map(|i| (i * 3 + 1) as u8).collect();
    flash.page_program(SCRATCH, &page).unwrap();
    flash.read_bytes(SCRATCH, &mut back[..256]).unwrap();
    check_true!(back[..256] == page[..], "read_bytes matches", passed, failed);
    flash.fast_read(SCRATCH, &mut back[..256]).unwrap();
    check_true!(back[..256] == page[..], "fast_read matches", passed, failed);
    flash.page_program(SCRATCH, &[0x0F, 0xF0, 0x00, 0xFF]).unwrap();
    flash.read_bytes(SCRATCH, &mut back[..4]).unwrap();
    check_true!(back[0] == page[0] & 0x0F && back[1] == page[1] & 0xF0 && back[2] == 0 && back[3] == page[3], "programming only clears bits (AND)", passed, failed);
    check_true!(flash.page_program(SCRATCH + 250, &page[..10]).is_err(), "page-crossing program refused", passed, failed);

    let data: Vec<u8> = (0..3000usize).map(|i| (i ^ (i >> 7)) as u8).collect();
    flash.write_bytes(SCRATCH + 4096 - 100, &data).unwrap();
    let mut got = vec![0u8; 3000];
    flash.read_bytes(SCRATCH + 4096 - 100, &mut got).unwrap();
    check_true!(got == data, "write_bytes across two sectors + read back", passed, failed);
    flash.write_bytes(SCRATCH + 4096 - 100, &data).unwrap();
    check_true!(flash.write_bytes(flash.size() - 2, &data[..5]).is_err(), "write past the end refused", passed, failed);

    flash.erase_block_32k(SCRATCH).unwrap();
    flash.erase_block_64k(SCRATCH).unwrap();
    flash.read_bytes(SCRATCH + 0xF000, &mut back[..16]).unwrap();
    check_true!(back[..16].iter().all(|&b| b == 0xFF), "64 KiB block blank", passed, failed);

    check_true!(flash.block_size() == 512 && flash.block_count() == 32768 && flash.erase_size() == 4096 && flash.needs_erase(), "BlockDevice geometry", passed, failed);
    let blk = SCRATCH / 512;
    let w: Vec<u8> = (0..1024usize).map(|i| (i * 5 + 2) as u8).collect();
    flash.write(blk, &w).unwrap();
    let mut r = vec![0u8; 1024];
    flash.read(blk, &mut r).unwrap();
    check_true!(r == w, "BlockDevice read back", passed, failed);
    check_true!(flash.write(blk, &[0xFF; 512]).is_err(), "0->1 without erase -> NotErased", passed, failed);
    flash.erase(blk, 8).unwrap();
    check_true!(flash.erase(blk + 1, 8).is_err(), "unaligned erase refused", passed, failed);
    flash.sync().unwrap();

    flash.set_protection(1).unwrap();
    check_true!(flash.page_program(SCRATCH + 0x8000, &[0]).is_err(), "program into protected area refused", passed, failed);
    check_true!(flash.erase_sector(SCRATCH + 0x8000).is_err(), "erase of protected sector refused", passed, failed);
    flash.set_protection(0).unwrap();
    flash.page_program(SCRATCH + 0x8000, &[0]).unwrap();
    flash.read_bytes(SCRATCH + 0x8000, &mut back[..1]).unwrap();
    check_true!(back[0] == 0, "program after unprotect", passed, failed);

    flash.deep_power_down().unwrap();
    flash.release_deep_power_down().unwrap();
    check_true!(flash.electronic_id().unwrap() == 0x17, "release_deep_power_down, RES again", passed, failed);

    flash.erase_block_64k(SCRATCH).unwrap();
    flash.wait_ready(1000).unwrap();
    println!("===DONE: {} passed, {} failed===", passed, failed);
}
