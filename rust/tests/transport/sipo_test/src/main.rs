use embedded_hal::digital::OutputPin;
use embedded_hal::spi::{ErrorType, SpiBus};
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, CdevPinError, SpidevBus};
use periph::transport::sipo::SiPoTransport;
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

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

/// Wraps CdevPinError so it satisfies embedded_hal::spi::Error (SpiBus
/// requires spi::ErrorType, not digital::ErrorType).
#[derive(Debug)]
struct BitBangSpiError(CdevPinError);

impl embedded_hal::spi::Error for BitBangSpiError {
    fn kind(&self) -> embedded_hal::spi::ErrorKind {
        embedded_hal::spi::ErrorKind::Other
    }
}

impl From<CdevPinError> for BitBangSpiError {
    fn from(e: CdevPinError) -> Self {
        BitBangSpiError(e)
    }
}

/// Minimal bit-banged SpiBus over two CdevPin lines (SER IN/SRCK), mode 0,
/// MSB-first — the same loop documented in specs/transport_sipo.md.
struct BitBangSpi {
    ser_in: CdevPin,
    srck: CdevPin,
}

impl ErrorType for BitBangSpi {
    type Error = BitBangSpiError;
}

impl SpiBus for BitBangSpi {
    fn read(&mut self, _words: &mut [u8]) -> Result<(), Self::Error> {
        Ok(())
    }

    fn write(&mut self, words: &[u8]) -> Result<(), Self::Error> {
        for &byte in words {
            for bit in (0..8).rev() {
                if (byte >> bit) & 1 == 1 {
                    self.ser_in.set_high()?;
                } else {
                    self.ser_in.set_low()?;
                }
                self.srck.set_high()?;
                self.srck.set_low()?;
            }
        }
        Ok(())
    }

    fn transfer(&mut self, _read: &mut [u8], write: &[u8]) -> Result<(), Self::Error> {
        self.write(write)
    }

    fn transfer_in_place(&mut self, words: &mut [u8]) -> Result<(), Self::Error> {
        self.write(words)
    }

    fn flush(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}

fn env_u32(name: &str, default: u32) -> u32 {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

fn main() {
    let mode = std::env::var("SIPO_MODE").unwrap_or_else(|_| "sw".into());
    let chip_path = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());

    let rck_offset = env_u32("SIPO_RCK", 5);
    let srclr_offset = env_u32("SIPO_SRCLR", 6);
    let g_offset = env_u32("SIPO_G", 13);
    let ser_in_offset = env_u32("SIPO_SER_IN", 19);
    let srck_offset = env_u32("SIPO_SRCK", 26);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let rck = CdevPin::new(
        chip.get_line(rck_offset).expect("get rck line")
            .request(LineRequestFlags::OUTPUT, 0, "sipo_test").expect("request rck"),
    ).expect("rck pin");
    let srclr = CdevPin::new(
        chip.get_line(srclr_offset).expect("get srclr line")
            .request(LineRequestFlags::OUTPUT, 1, "sipo_test").expect("request srclr"),
    ).expect("srclr pin");
    let g = CdevPin::new(
        chip.get_line(g_offset).expect("get g line")
            .request(LineRequestFlags::OUTPUT, 0, "sipo_test").expect("request g"),
    ).expect("g pin");

    if mode == "hw" {
        let spi_bus = env_u32("SIPO_SPI_BUS", 0);
        let spi_device = env_u32("SIPO_SPI_DEVICE", 0);
        let mut spi = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device))
            .expect("open spidev");
        spi.configure(
            &SpidevOptions::new()
                .max_speed_hz(1_000_000)
                .mode(SpiModeFlags::SPI_MODE_0)
                .build(),
        )
        .expect("configure spidev");
        let bus = SpidevBus(spi);

        let mut transport =
            SiPoTransport::new(bus, rck, Some(srclr), Some(g)).expect("new transport");
        run_checks(&mut transport, &mut passed, &mut failed);
    } else {
        let ser_in = CdevPin::new(
            chip.get_line(ser_in_offset).expect("get ser_in line")
                .request(LineRequestFlags::OUTPUT, 0, "sipo_test").expect("request ser_in"),
        ).expect("ser_in pin");
        let srck = CdevPin::new(
            chip.get_line(srck_offset).expect("get srck line")
                .request(LineRequestFlags::OUTPUT, 0, "sipo_test").expect("request srck"),
        ).expect("srck pin");
        let bus = BitBangSpi { ser_in, srck };

        let mut transport =
            SiPoTransport::new(bus, rck, Some(srclr), Some(g)).expect("new transport");
        run_checks(&mut transport, &mut passed, &mut failed);
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}

fn run_checks<SPI, RCK, SRCLR, G>(
    transport: &mut SiPoTransport<SPI, RCK, SRCLR, G>,
    passed: &mut i32,
    failed: &mut i32,
) where
    SPI: embedded_hal::spi::SpiBus,
    RCK: OutputPin,
    SRCLR: OutputPin,
    G: OutputPin,
{
    check_true!(transport.write(&[0xA5]).is_ok(), "write accepted", *passed, *failed);
    check_true!(
        transport.write(&[0x00, 0xFF]).is_ok(),
        "write multi-byte accepted",
        *passed,
        *failed
    );
    check_true!(transport.clear().is_ok(), "clear accepted", *passed, *failed);
    check_true!(
        transport.set_output_enable(false).is_ok(),
        "set_output_enable(false) accepted",
        *passed,
        *failed
    );
    check_true!(
        transport.set_output_enable(true).is_ok(),
        "set_output_enable(true) accepted",
        *passed,
        *failed
    );
}
