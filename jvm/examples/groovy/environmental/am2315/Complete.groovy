///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.AM2315Full

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x5C").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new AM2315Full(conn)                                        // Create AM2315 driver, (connection) → void

    def v = sensor.read()                                                    // Read temperature and humidity, () → double[] {°C, %RH}
    // previous measurement; reads are kept >= 2 s apart, CRC-16 checked
    printf("%.1f C, %.1f %%RH%n", v[0], v[1])
    def t = sensor.readTemperature()                                         // Read temperature only, () → double °C
    def rh = sensor.readHumidity()                                           // Read humidity only, () → double %RH
    printf("%.1f C, %.1f %%RH%n", t, rh)
    def raw = sensor.readRaw()                                               // Read raw words, () → int[] {humidity x10, temperature x10 sign + magnitude}
    printf("raw RH %d, raw T 0x%04X%n", raw[0], raw[1])
    def fresh = sensor.readFresh()                                           // Real-time value, () → double[] {°C, %RH}
    // two reads >= 2 s apart; returns the second
    printf("fresh %.1f C, %.1f %%RH%n", fresh[0], fresh[1])
    def regs = sensor.readRegisters(0x08, 7)                                 // Read registers, (start, count) → byte[]
    // function 0x03, 1-10 registers, CRC verified
    println("registers 0x08.. " + regs.toList())
    def info = sensor.readDeviceInfo()                                       // Read device information, () → long[] {model, version, device id}
    printf("model %d, version %d, device id 0x%08X%n", info[0], info[1], info[2])
    sensor.writeUserRegisters(0x1234, 0xABCD)                                // Write user registers, (user1, user2) → void
    def user = sensor.readUserRegisters()                                    // Read user registers, () → int[] {user1, user2}
    printf("user registers 0x%04X 0x%04X%n", user[0], user[1])
    sensor.writeRegisters(0x10, [0x00, 0x01] as byte[])                      // Write registers, (start, data) → void
    // only the user registers 0x10-0x13 are allowed
    sensor.wake()                                                            // Wake the sensor, () → void
    // dummy write, the NACK is ignored
} finally {
    conn.close()
}
