//! Hardware smoke test of the UC8159 driver on a Linux host (spidev + gpio-cdev).
//!
//! Needs a panel: `show()` waits for the real refresh (about 30 s).
//! Wire D/C#, RES# and BUSY to the GPIO lines below (env overrides).
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::uc8159::{Uc8159Full, BLACK, ORANGE, RED};
use periph::connection::display::{DisplayConnection, WireMode};
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

    let connection = DisplayConnection::new(device, dc, Some(res), Some(busy), Delay, WireMode::Simplex)
        .expect("new connection");
    let mut fb = vec![0u8; periph::chips::display::uc8159::FRAME_BYTES];
    let mut chip = Uc8159Full::new(connection, &mut fb, Some(60_000))
        .expect("init");
    check_true!(true, "init_valid", passed, failed);

    chip.clear(periph::chips::display::uc8159::WHITE);
    chip.set_pixel(1, 1, BLACK);
    check_true!(chip.get_pixel(1, 1) == BLACK, "pixel_black_readback", passed, failed);
    chip.set_pixel(3, 1, RED);
    check_true!(chip.get_pixel(3, 1) == RED, "pixel_red_readback", passed, failed);
    chip.set_pixel(4, 1, ORANGE);
    check_true!(chip.get_pixel(4, 1) == ORANGE, "pixel_orange_readback", passed, failed);
    chip.draw_rect(0, 0, 600, 448, BLACK, false);
    chip.draw_line(0, 0, 599, 447, BLACK);
    check_true!(chip.get_pixel(0, 0) == BLACK, "drawing_accepted", passed, failed);
    check_true!(chip.show().is_ok(), "show_completes", passed, failed);
    check_true!(chip.sleep().is_ok(), "sleep_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
