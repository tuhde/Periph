use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::rfid::{Mfrc522Full, RX_GAIN_18_DB, RX_GAIN_23_DB, RX_GAIN_33_DB, RX_GAIN_38_DB, RX_GAIN_43_DB, RX_GAIN_48_DB};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let dev_path = std::env::var("SPI_DEV").unwrap_or_else(|_| "/dev/spidev0.0".to_string());

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus, NullCs).expect("spi device");

    let mut mfrc = Mfrc522Full::new(device).expect("init MFRC522");

    let (chip, ver) = mfrc.version().unwrap_or((0, 0));
    check_true!(chip == 0x09, "chip_type == 0x09 (MFRC522)", passed, failed);
    check_true!(ver == 1 || ver == 2, "version in {1, 2}", passed, failed);

    mfrc.antenna_on().expect("antenna_on");
    mfrc.antenna_off().expect("antenna_off");
    mfrc.antenna_on().expect("antenna_on");

    for gain in [RX_GAIN_18_DB, RX_GAIN_23_DB, RX_GAIN_33_DB, RX_GAIN_38_DB, RX_GAIN_43_DB, RX_GAIN_48_DB] {
        mfrc.set_antenna_gain(gain).expect("set_antenna_gain");
        check_true!(mfrc.antenna_gain().unwrap_or(0xFF) == gain, "antenna_gain read back", passed, failed);
    }

    let _present = mfrc.is_card_present();

    mfrc.reset().expect("reset");
    check_true!(true, "reset accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
