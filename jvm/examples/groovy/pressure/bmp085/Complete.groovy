///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp085Full

def conn = new I2CConnection(1, 0x77)
try {
    def sensor = new Bmp085Full(conn)

    int id = sensor.chipId()
    println "chip ID: 0x${Integer.toHexString(id).toUpperCase()}"

    int oss = sensor.oversampling()
    println "oversampling: $oss"

    sensor.setOversampling(Bmp085Full.OSS_HIGH_RES)

    double t = sensor.temperature()
    printf "temperature: %.2f °C%n", t

    double p = sensor.pressure()
    printf "pressure: %.2f Pa%n", p

    double alt = sensor.altitude()
    printf "altitude: %.1f m%n", alt

    double altRef = sensor.altitude(101300.0)
    printf "altitude (QNH 101300.0): %.1f m%n", altRef

    double slp = sensor.seaLevelPressure(50.0)
    printf "sea-level pressure at 50 m: %.2f Pa%n", slp

    sensor.reset()

    sensor.setOversampling(Bmp085Full.OSS_ULP)

    println "oversampling after reset: ${sensor.oversampling()}"
} finally {
    conn.close()
}