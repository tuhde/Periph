//! Smoke test of the display connection on a Linux host (spidev + gpio-cdev).
//!
//! Checks that every operation is accepted and that BUSY is readable and
//! `wait_busy` returns within its timeout; it does not verify the controller's
//! response. Wire D/C#, RES# and BUSY to the GPIO lines below (env overrides).
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::connection::display::{DisplayConnection, DisplayError, WireMode};
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

// Dummy CS: spidev manages CS# in the kernel.
struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
    fn set_high(&mut self) -> Result<(), Self::Error> {
        Ok(())
    }
}

fn env_u32(name: &str, default: u32) -> u32 {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

fn main() {
    let chip_path = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let spi_bus = env_u32("SPI_BUS", 0);
    let spi_device = env_u32("SPI_DEVICE", 0);
    let dc_offset = env_u32("DISPLAY_DC", 25);
    let res_offset = env_u32("DISPLAY_RES", 24);
    let busy_offset = env_u32("DISPLAY_BUSY", 23);

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let dc = CdevPin::new(
        chip.get_line(dc_offset).expect("dc line")
            .request(LineRequestFlags::OUTPUT, 0, "display_test").expect("request dc"),
    ).expect("dc pin");
    let res = CdevPin::new(
        chip.get_line(res_offset).expect("res line")
            .request(LineRequestFlags::OUTPUT, 1, "display_test").expect("request res"),
    ).expect("res pin");
    let busy = CdevPin::new(
        chip.get_line(busy_offset).expect("busy line")
            .request(LineRequestFlags::INPUT, 0, "display_test").expect("request busy"),
    ).expect("busy pin");

    let mut spi = Spidev::open(format!("/dev/spidev{}.{}", spi_bus, spi_device)).expect("open spidev");
    spi.configure(
        &SpidevOptions::new()
            .max_speed_hz(4_000_000)
            .mode(SpiModeFlags::SPI_MODE_0)
            .build(),
    )
    .expect("configure spidev");
    let device = ExclusiveDevice::new_no_delay(SpidevBus(spi), NullCs).expect("spi device");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut c = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay, WireMode::Simplex)
        .expect("new connection");

    check_true!(c.reset().is_ok(), "reset_accepted", passed, failed);
    check_true!(c.send_command(0x12, &[]).is_ok(), "command_accepted", passed, failed);
    check_true!(
        c.send_command(0x04, &[0x01, 0x02]).is_ok(),
        "command_with_parameters_accepted",
        passed,
        failed
    );
    check_true!(c.send_data(&[0xFF; 16]).is_ok(), "data_accepted", passed, failed);
    check_true!(c.busy_level().is_ok(), "busy_level_readable", passed, failed);
    check_true!(
        c.wait_busy(false, Some(50)).is_ok(),
        "wait_busy_returns_within_timeout",
        passed,
        failed
    );
    let mut buf = [0u8; 1];
    check_true!(
        matches!(c.read_data(&mut buf), Err(DisplayError::ReadUnavailable)),
        "read_data_unavailable_in_simplex",
        passed,
        failed
    );
    c.disable();
    check_true!(!c.is_enabled(), "disable_gates_bus", passed, failed);
    c.enable();
    check_true!(c.is_enabled(), "enable_resumes", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
