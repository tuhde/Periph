use linux_embedded_hal::I2cdev;
use periph::chips::memory::Mb85rcFull;

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

macro_rules! check_eq {
    ($label:expr, $got:expr, $expected:expr, $passed:expr, $failed:expr) => {
        if $got == $expected {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}: got {:?}, expected {:?}", $label, $got, $expected);
            $failed += 1;
        }
    };
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x50);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Mb85rcFull::new(dev, addr);

    let mut passed = 0i32;
    let mut failed = 0i32;

    check_eq!("size", chip.size(), 32768usize, passed, failed);

    chip.write_byte(0x0010, 0x5A).expect("write_byte");
    check_eq!("write_byte_read_byte_round_trip", chip.read_byte(0x0010).unwrap(), 0x5Au8, passed, failed);

    let data: Vec<u8> = (0..100).collect();
    chip.write(0x0100, &data).expect("write");
    let mut back = vec![0u8; 100];
    chip.read(0x0100, &mut back).expect("read");
    check_eq!("chunked_write_read_round_trip", back, data, passed, failed);

    chip.fill(0x0100, 100, 0xC3).expect("fill");
    chip.read(0x0100, &mut back).expect("read");
    check_eq!("fill", back, vec![0xC3u8; 100], passed, failed);
    chip.fill(0x0100, 100, 0).expect("fill");
    chip.read(0x0100, &mut back).expect("read");
    check_eq!("fill_zero", back, vec![0u8; 100], passed, failed);

    chip.write(0x7FFE, &[0x12, 0x34]).expect("write");
    let mut tail = [0u8; 2];
    chip.read(0x7FFE, &mut tail).expect("read");
    check_eq!("last_bytes_round_trip", tail, [0x12u8, 0x34], passed, failed);

    let mut wrap = [0u8; 2];
    check_true!(chip.read(0x7FFF, &mut wrap).is_err(), "wrap_rejected", passed, failed);

    match chip.read_device_id() {
        Ok(id) => check_eq!("read_device_id", id, (0x00Au16, 0x5u8, 0x10u8), passed, failed),
        Err(_) => println!("SKIP read_device_id (0x7C not accessible on this host)"),
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
