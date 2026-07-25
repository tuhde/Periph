#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::uart::{Config, Uart};
use esp_println::println;
use periph::chips::gnss::{Neo6Full, UartBus};
use periph::transport::uart_linux::LinuxUart;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let uart = Uart::new(peripherals.UART1, Config::default().with_baudrate(9600))
        .unwrap()
        .with_tx(peripherals.GPIO17)
        .with_rx(peripherals.GPIO18)
        .into_blocking();
    let mut delay = Delay::new();

    // --- Portable GPS logger ---
    // The module self-configures at factory defaults (9600 baud NMEA, 1 Hz);
    // no CFG messages are needed for a basic position log. Runs for 60
    // seconds, polling update() far faster than the 1 Hz sentence rate so
    // no sentence is missed, and prints one line per second once a fresh
    // GGA has been parsed.
    let mut gps = Neo6Full::new(UartBus(uart)); // Create NEO-6 driver, (bus: UartBus/I2cBus/SpiBus)

    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(60) {
        let got_fix = gps.update().expect("update"); // Read + parse one NMEA sentence, () → Result<bool, Error>

        // --- No fix yet: show the wait state ---
        // gpsFix alone would not be trustworthy here; update() already only
        // reports true once the GGA fix-status field confirms a real fix,
        // so a plain fix() == 0 check is enough to detect the waiting state.
        if gps.fix() == 0 {
            println!("waiting for fix... satellites in use: {}", gps.satellites());
        } else if got_fix {
            // --- Fix acquired: log the full position record ---
            // Cold-start TTFF is ~26 s typical outdoors; once got_fix flips
            // true the position, altitude, and HDOP fields below are all
            // populated together.
            println!(
                "{:?}  lat={:.6?}  lon={:.6?}  alt={:.1?} m  sats={}  hdop={:?}",
                gps.utc_time(),
                gps.latitude(),
                gps.longitude(),
                gps.altitude(),
                gps.satellites(),
                gps.hdop()
            );
        }

        delay.delay_ms(200);
    }
    loop {}
}
