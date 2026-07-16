// To use I2C (DDC) instead of UART, construct with periph::chips::gnss::I2cBus { i2c, addr: 0x42 }.
// To use SPI instead of UART, construct with periph::chips::gnss::SpiBus(spi_device).

use periph::chips::gnss::{Neo6Full, UartBus};
use periph::transport::uart_linux::LinuxUart;

fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let baud: u32 = std::env::var("UART_BAUD").ok().and_then(|v| v.parse().ok()).unwrap_or(9600);

    let uart = LinuxUart::open(&port, baud).expect("open serial port");
    let mut gps = Neo6Full::new(UartBus(uart)); // Create NEO-6 driver, (bus: UartBus/I2cBus/SpiBus)

    gps.set_rate(1).expect("set rate");             // Set navigation update rate, (hz) → Result<(), Error>
                                                      // writes CFG-RATE with measRate = 1000/hz ms
    gps.set_platform(0).expect("set platform");      // Set dynamic platform model, (model 0-8) → Result<(), Error>
                                                      // writes CFG-NAV5 with mask=dynModel only
    gps.save_config().expect("save config");         // Persist current configuration, () → Result<(), Error>
                                                      // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM

    for _ in 0..20 {
        if gps.update().expect("update") {
            // Read + parse one NMEA sentence, () → Result<bool, Error>
            println!("{:?} {:?} {:?}", gps.latitude(), gps.longitude(), gps.altitude());
            println!("{:?} {:?}", gps.speed(), gps.course()); // Speed / course over ground, () → Option<f32> m/s / deg
            println!("{:?} {:?}", gps.utc_time(), gps.utc_date()); // UTC time / date, () → Option<&str>
            println!("{:?}", gps.hdop()); // Horizontal dilution of precision, () → Option<f32>
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }

    let nav_status = gps.poll_ubx(0x01, 0x03).expect("poll NAV-STATUS"); // Poll a UBX message, (msg_class, msg_id) → Result<UbxPayload, Error>
    println!("NAV-STATUS payload: {:?}", nav_status.as_slice());

    gps.cold_start().expect("cold start"); // Force a cold start via CFG-RST, () → Result<(), Error>
}
