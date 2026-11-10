use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::motor::{Tmc5160Full, Tmc5160NoPin};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

fn pin(chip: &mut Chip, line: u32, name: &str, flags: LineRequestFlags) -> CdevPin {
    let handle = chip.get_line(line).expect("get line")
        .request(flags, 0, name).expect("request line");
    CdevPin::new(handle).expect("pin")
}

fn open_spi() -> ExclusiveDevice<SpidevBus, NullCs, embedded_hal_bus::spi::NoDelay> {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", bus, dev)).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_3)
        .build()).expect("configure spi");
    ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).expect("spi device")
}

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let mut chip = Chip::new("/dev/gpiochip0").expect("open gpio chip");
    let en = pin(&mut chip, 17, "tmc5160_en", LineRequestFlags::OUTPUT);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut motor = Tmc5160Full::new_spi(open_spi(), Delay, 0.075, Some(en), None::<Tmc5160NoPin>, None::<Tmc5160NoPin>)
        .expect("init"); // Create TMC5160 Full driver, (spi, delay, r_sense_ohm=0.075 Ω, en_pin, diag0_pin, diag1_pin) → Result
    check_true!(true, "init", passed, failed);

    let ioin = motor.read_ioin().unwrap(); // Read IOIN, () → Result<Tmc5160Ioin, Error>
    check_true!(ioin.version == 0x30, "ioin_version", passed, failed);
    motor.write_register(0x13, 600, true).unwrap(); // Write a raw register, (reg, value, verify) → Result<(), Error>
    check_true!(true, "verified_write", passed, failed);
    motor.set_microsteps(16).unwrap(); // Set microstep resolution, (n 1–256) → Result<(), Error>
    check_true!((motor.read_register(0x6C).unwrap() >> 24) & 0xF == 4, "mres_readback", passed, failed);
    check_true!(motor.read_register(0x00).unwrap() & 0x4 == 0x4, "gconf_readback", passed, failed);
    motor.set_microsteps(256).unwrap();
    motor.enable().unwrap(); // Power stage on, () → Result<(), Error>
    motor.set_position(0.0).unwrap(); // Define position, (position full steps) → Result<(), Error>
    motor.move_to(100.0).unwrap(); // Start positioning move, (position full steps) → Result<(), Error>
    check_true!(motor.wait_until_reached(None).is_ok(), "move_reached", passed, failed);
    check_true!((motor.position().unwrap() - 100.0).abs() < 1.0, "position_readback", passed, failed);
    check_true!(motor.read_fault().unwrap() == 0, "no_fault", passed, failed);
    check_true!(motor.read_stallguard().unwrap() <= 1023, "sg_result_range", passed, failed);
    motor.disable().unwrap(); // Power stage off, () → Result<(), Error>
    check_true!(motor.read_status().is_ok(), "status_readable", passed, failed);
    check_true!(motor.read_global_status().is_ok(), "gstat_readable", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
}
