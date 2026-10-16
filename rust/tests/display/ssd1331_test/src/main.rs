//! Hardware smoke test of the SSD1331 driver on a Linux host (spidev + gpio-cdev).
//!
//! The picture itself is verified by eye (see the demo example).
//! Wire D/C# and RES# to the GPIO lines below (env overrides); the chip has no BUSY line.
use embedded_hal_bus::spi::ExclusiveDevice;
use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::{CdevPin, Delay, SpidevBus};
use periph::chips::display::ssd1331::{DisplayMode, Ssd1331Full, BLUE, BUFFER_SIZE, GREEN, RED, WHITE, YELLOW};
use periph::connection::display::{DisplayConnection, NoPin, WireMode};
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

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");
    let dc = CdevPin::new(
        chip.get_line(dc_offset).expect("dc line")
            .request(LineRequestFlags::OUTPUT, 0, "display_test").expect("request dc"),
    ).expect("dc pin");
    let res = CdevPin::new(
        chip.get_line(res_offset).expect("res line")
            .request(LineRequestFlags::OUTPUT, 1, "display_test").expect("request res"),
    ).expect("res pin");

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

    let connection = DisplayConnection::new(device, dc, Some(res), None::<NoPin>, Delay, WireMode::Simplex)
        .expect("new connection");
    let mut fb = [0u8; BUFFER_SIZE];
    let mut chip = Ssd1331Full::new(connection, &mut fb)
        .expect("init");
    check_true!(true, "init_valid", passed, failed);

    chip.clear(BLUE);
    check_true!(chip.get_pixel(0, 0) == BLUE && chip.get_pixel(95, 63) == BLUE, "clear_readback", passed, failed);
    chip.set_pixel(5, 6, RED);
    check_true!(chip.get_pixel(5, 6) == RED, "pixel_readback", passed, failed);
    chip.draw_rect(10, 10, 20, 10, GREEN, true);
    check_true!(chip.get_pixel(15, 15) == GREEN, "draw_rect_fill", passed, failed);
    chip.draw_line(0, 0, 95, 63, WHITE);
    check_true!(chip.get_pixel(0, 0) == WHITE && chip.get_pixel(95, 63) == WHITE, "draw_line_endpoints", passed, failed);
    let ok = chip.show().is_ok()
        && chip.show_region(8, 8, 32, 16).is_ok()
        && chip.hw_clear(0, 0, 95, 63).is_ok()
        && chip.hw_rect(10, 10, 40, 30, YELLOW, RED, true).is_ok()
        && chip.hw_line(0, 0, 95, 63, WHITE).is_ok()
        && chip.hw_copy(10, 10, 30, 30, 50, 20).is_ok()
        && chip.hw_dim(0, 0, 47, 63).is_ok()
        && chip.set_contrast(0x80, 0x80, 0x80).is_ok()
        && chip.set_master_current(7).is_ok()
        && chip.set_display_mode(DisplayMode::Inverse).is_ok()
        && chip.set_display_mode(DisplayMode::Normal).is_ok()
        && chip.set_flip(false, false).is_ok()
        && chip.scroll(1, 0, 64, 0, 0).is_ok()
        && chip.scroll_stop().is_ok()
        && chip.display_off().is_ok()
        && chip.display_on().is_ok();
    check_true!(ok, "commands_accepted", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
