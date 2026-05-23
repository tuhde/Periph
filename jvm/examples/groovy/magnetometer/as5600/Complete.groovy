///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.magnetometer.As5600Full

def transport = new I2CTransport(1, 0x36)                // open I²C bus 1, device 0x36, (bus, address) → I2CTransport
try {
    def as5600 = new As5600Full(transport)                     // construct driver, (transport) → As5600Full

    double angle = as5600.angle()                          // read angle in degrees, () → double deg
                                                           // scaled by ZPOS/MPOS range, 0.0–360.0
    int raw = as5600.angleRaw()                            // read raw angle count, () → int 0–4095
                                                           // 12-bit value from ANGLE register
    printf("angle=%.2f°  raw=%d%n", angle, raw)

    int rawAngle = as5600.rawAngle()                       // read unscaled raw angle, () → int 0–4095
                                                           // from RAW_ANGLE register, unaffected by ZPOS/MPOS
    double rawDeg = as5600.rawAngleDegrees()               // read raw angle in degrees, () → double deg
                                                           // rawAngle × 360 / 4096
    printf("raw_angle=%d  raw_deg=%.2f°%n", rawAngle, rawDeg)

    int agc = as5600.agc()                                 // read AGC value, () → int 0–255 (5V) or 0–127 (3.3V)
                                                           // mid-range = optimal airgap
    int mag = as5600.magnitude()                           // read CORDIC magnitude, () → int 0–4095
                                                           // 12-bit magnitude from CORDIC computation
    int status = as5600.statusByte()                       // read raw STATUS byte, () → int
                                                           // MH(bit5), ML(bit4), MD(bit3)
    printf("agc=%d  magnitude=%d  status=0x%02X%n", agc, mag, status)

    printf("magnet_detected=%s  too_strong=%s  too_weak=%s%n",
            as5600.isMagnetDetected(),                     // check magnet detected, () → boolean
            as5600.isMagnetTooStrong(),                    // check magnet too strong, () → boolean
            as5600.isMagnetTooWeak())                      // check magnet too weak, () → boolean

    int zpos = as5600.zeroPosition()                       // read zero position, () → int 0–4095
    int mpos = as5600.maxPosition()                        // read max position, () → int 0–4095
    int mang = as5600.maxAngle()                           // read max angle span, () → int 0–4095
    printf("zpos=%d  mpos=%d  mang=%d%n", zpos, mpos, mang)

    as5600.setZeroPosition(0)                              // set zero position, (pos=0) → void
                                                           // writes ZPOS to volatile RAM; lost on power cycle
    as5600.setMaxPosition(2048)                            // set max position, (pos=2048) → void
                                                           // writes MPOS to volatile RAM; 180° range
    Thread.sleep(2)  // wait ≥1 ms for register to take effect

    int bc = as5600.burnCount()                            // read burn count, () → int 0–3
                                                           // number of permanent ZPOS/MPOS writes done
    printf("burn_count=%d (remaining=%d)%n", bc, 3 - bc)

    as5600.configure(                                      // configure chip, (pm=0, hyst=0, outs=0, pwmf=0, sf=0, fth=0, wd=false) → void
            As5600Full.PM_NOM, As5600Full.HYST_OFF,
            As5600Full.OUTS_ANALOG, As5600Full.PWMF_115HZ,
            As5600Full.SF_16X, 0, false)
                                                           // NOM mode, no hysteresis, analog output, 115 Hz PWM, 16× slow filter

} finally {
    transport.close()
}
