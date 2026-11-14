use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::memory::Cat24c512Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut delay = Delay;
    let mut chip = Cat24c512Full::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    macro_rules! check_true {
        ($cond:expr, $label:expr) => {
            if $cond { println!("PASS {}", $label); passed += 1; }
            else      { println!("FAIL {}", $label); failed += 1; }
        };
    }

    check_true!(chip.size() == 65536, "size");
    check_true!(chip.page_size() == 128, "page_size");

    chip.write_byte(0x0010, 0x5A, &mut delay).expect("write_byte");
    check_true!(chip.read_byte(0x0010).expect("read_byte") == 0x5A, "write_byte/read_byte round-trip");
    check_true!(chip.wait_ready(10, &mut delay), "wait_ready after write");

    let mut data = [0u8; 200];
    for (i, b) in data.iter_mut().enumerate() {
        *b = i as u8;
    }
    chip.write(0x0070, &data, &mut delay).expect("write");
    let mut back = [0u8; 200];
    chip.read(0x0070, &mut back).expect("read");
    check_true!(back == data, "page-split write/read round-trip");

    let mut page = [0u8; 128];
    for (i, b) in page.iter_mut().enumerate() {
        *b = i as u8;
    }
    chip.write_page(0x0300, &page, &mut delay).expect("write_page");
    let mut pb = [0u8; 128];
    chip.read(0x0300, &mut pb).expect("read page");
    check_true!(pb == page, "write_page round-trip");
    check_true!(chip.write_page(0x037C, &[0u8; 8], &mut delay).is_err(), "write_page crossing rejected");

    chip.fill(0x0100, 100, 0xC3, &mut delay).expect("fill");
    let mut f = [0u8; 100];
    chip.read(0x0100, &mut f).expect("read fill");
    check_true!(f.iter().all(|b| *b == 0xC3), "fill");
    chip.fill(0x0100, 100, 0, &mut delay).expect("fill zero");
    chip.read(0x0100, &mut f).expect("read fill");
    check_true!(f.iter().all(|b| *b == 0), "fill zero");

    chip.write(0xFFFE, &[0x12, 0x34], &mut delay).expect("write tail");
    let mut tail = [0u8; 2];
    chip.read(0xFFFE, &mut tail).expect("read tail");
    check_true!(tail == [0x12, 0x34], "last bytes round-trip");
    check_true!(chip.read(0xFFFF, &mut tail).is_err(), "wrap rejected");

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
