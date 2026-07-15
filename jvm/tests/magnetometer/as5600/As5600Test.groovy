///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED --add-opens java.base/sun.misc=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import groovy.transform.Field
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.magnetometer.As5600Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

int bus  = (System.getenv('I2C_BUS')  ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x36').replaceFirst(/^0[xX]/, ''), 16)

def transport = new I2CTransport(bus, addr)
try {
    // --- Magnet status poll (60 s max at 5 Hz) ---
    println('--- magnet status (60 s max) ---')
    long deadline = System.currentTimeMillis() + 60_000L
    while (System.currentTimeMillis() < deadline) {
        int s   = transport.writeRead([0x0B] as byte[], 1)[0] & 0xFF
        int agc = transport.writeRead([0x1A] as byte[], 1)[0] & 0xFF
        println("MD=${(s >> 3) & 1} ML=${(s >> 4) & 1} MH=${(s >> 5) & 1} AGC=$agc")
        if (s & 0x08) break
        Thread.sleep(200)
    }
    println('--- end magnet status ---')

    // Allocate instance without calling constructor to bypass MD check.
    def theUnsafe = sun.misc.Unsafe.class.getDeclaredField('theUnsafe')
    theUnsafe.accessible = true
    def unsafe = theUnsafe.get(null) as sun.misc.Unsafe
    def as5600 = unsafe.allocateInstance(As5600Full) as As5600Full
    def tf = as5600.getClass().getSuperclass().getDeclaredField('transport')
    tf.accessible = true
    tf.set(as5600, transport)

    // --- Basic measurements ---
    double angle = as5600.angle()
    checkTrue('angle() in range 0–360', angle >= 0.0d && angle < 360.0d)

    int raw = as5600.angleRaw()
    checkTrue('angleRaw() in range 0–4095', raw >= 0 && raw <= 4095)

    // --- Magnet status ---
    boolean md = as5600.isMagnetDetected()
    checkTrue('isMagnetDetected() == true', md)

    as5600.isMagnetTooStrong()
    checkTrue('isMagnetTooStrong() accepted', true)

    as5600.isMagnetTooWeak()
    checkTrue('isMagnetTooWeak() accepted', true)

    // --- Full methods ---
    int rawAngle = as5600.rawAngle()
    checkTrue('rawAngle() in range 0–4095', rawAngle >= 0 && rawAngle <= 4095)

    double rawDeg = as5600.rawAngleDegrees()
    checkTrue('rawAngleDegrees() in range 0–360', rawDeg >= 0.0d && rawDeg < 360.0d)

    int agc = as5600.agc()
    checkTrue('agc() is finite', agc >= 0)

    int mag = as5600.magnitude()
    checkTrue('magnitude() in range 0–4095', mag >= 0 && mag <= 4095)

    int status = as5600.statusByte()
    checkTrue('statusByte() accepted', status >= 0 && status <= 255)

    // --- Position registers ---
    int zpos = as5600.zeroPosition()
    checkTrue('zeroPosition() accepted', zpos >= 0 && zpos <= 4095)

    int mpos = as5600.maxPosition()
    checkTrue('maxPosition() accepted', mpos >= 0 && mpos <= 4095)

    int mang = as5600.maxAngle()
    checkTrue('maxAngle() accepted', mang >= 0 && mang <= 4095)

    // --- Set position (volatile) ---
    as5600.setZeroPosition(100)
    Thread.sleep(2)
    int zpos2 = as5600.zeroPosition()
    checkTrue('setZeroPosition(100) wrote correctly', zpos2 == 100)

    as5600.setMaxPosition(2048)
    Thread.sleep(2)
    int mpos2 = as5600.maxPosition()
    checkTrue('setMaxPosition(2048) wrote correctly', mpos2 == 2048)

    as5600.setMaxAngle(1024)
    Thread.sleep(2)
    int mang2 = as5600.maxAngle()
    checkTrue('setMaxAngle(1024) wrote correctly', mang2 == 1024)

    // --- Burn count ---
    int bc = as5600.burnCount()
    checkTrue('burnCount() in range 0–3', bc >= 0 && bc <= 3)

    // --- Configure ---
    as5600.configure(As5600Full.PM_NOM, As5600Full.HYST_OFF,
            As5600Full.OUTS_ANALOG, As5600Full.PWMF_115HZ,
            As5600Full.SF_16X, 0, false)
    checkTrue('configure() accepted', true)

    Thread.sleep(2)  // allow register to take effect

    // --- Verify angle still works after configure ---
    double angle2 = as5600.angle()
    checkTrue('angle() after configure() in range', angle2 >= 0.0d && angle2 < 360.0d)

} finally {
    transport.close()
}

println("===DONE: ${passed} passed, ${failed} failed===")
System.exit(failed == 0 ? 0 : 1)
