///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.light.Apds9960Full

def transport = new I2CTransport(1, 0x39)               // open I²C bus 1, device 0x39, (bus, address) → I2CTransport
try {
    def apds = new Apds9960Full(transport)                     // construct driver, (transport) → Apds9960Full

    printf("chip_id: 0x%02X%n", apds.chipId())                 // read device ID, () → int

    int[] rgbc = apds.color()                                  // read all RGBC channels, () → int[] [clear, red, green, blue]
                                                               // burst read 0x94-0x9B latches all channels atomically
    printf("C=%d R=%d G=%d B=%d%n", rgbc[0], rgbc[1], rgbc[2], rgbc[3])
    println("clear: " + apds.colorClear())                     // read clear channel, () → int 0-65535
    println("red: " + apds.colorRed())                         // read red channel, () → int 0-65535
    println("green: " + apds.colorGreen())                     // read green channel, () → int 0-65535
    println("blue: " + apds.colorBlue())                       // read blue channel, () → int 0-65535

    apds.configureAls(0xB6, 1)                                 // configure ALS, (atime 0-255, again 0-3) → void
                                                               // sets integration time and gain for the ALS/color engine
    apds.configureWait(0xFF, false)                            // configure wait, (wtime 0-255, wlong=false) → void
                                                               // sets idle period between measurement cycles
    apds.enableWait(true)                                      // enable wait engine, (enabled) → void

    apds.enableProximity(true)                                 // enable proximity engine, (enabled) → void
    apds.configureProximityLed(0, 0, 0, 1)                     // configure proximity LED, (ldrive 0-3, pgain 0-3, ppulse 0-63, pplen 0-3) → void
                                                               // sets LED drive strength, gain, pulse count and length
    apds.setLedBoost(0)                                        // set LED boost, (boost 0-3) → void
                                                               // multiplies LED current: 0=100%, 1=150%, 2=200%, 3=300%
    println("proximity: " + apds.proximity())                  // read proximity count, () → int 0-255

    apds.alsThreshold(100, 60000)                              // set ALS thresholds, (low 0-65535, high 0-65535) → void
    apds.proximityThreshold(10, 200)                           // set proximity thresholds, (low 0-255, high 0-255) → void
    apds.setPersistence(0, 1)                                  // set persistence, (ppers 0-15, apers 0-15) → void

    apds.enableAlsInterrupt(true)                              // enable ALS interrupt, (enabled) → void
    apds.enableProximityInterrupt(true)                        // enable proximity interrupt, (enabled) → void
    apds.clearAlsInterrupt()                                   // clear ALS interrupt, () → void
    apds.clearProximityInterrupt()                             // clear proximity interrupt, () → void
    apds.clearAllInterrupts()                                  // clear all interrupts, () → void

    apds.setProximityOffset(10, -5)                            // set proximity offset, (ur -127..127, dl -127..127) → void
                                                               // sign-magnitude encoding compensates for optical crosstalk
    apds.setProximityMask(false, false, false, false)          // set proximity mask, (u, d, l, r) → void

    apds.enableGesture(true)                                   // enable gesture engine, (enabled) → void
    apds.configureGesture(1, 0, 0, 1, 1, 50, 20)              // configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → void
                                                               // sets gain, LED drive, pulse, wait time, entry/exit thresholds
    println("gesture_available: " + apds.gestureAvailable())   // check gesture data, () → boolean
    println("gesture_fifo_level: " + apds.gestureFifoLevel())  // read FIFO level, () → int
    def fifo = apds.readGestureFifo()                          // read gesture FIFO, () → List<int[]>
    apds.clearGestureFifo()                                    // clear gesture FIFO, () → void
    apds.enableGestureInterrupt(false)                         // enable gesture interrupt, (enabled) → void
    apds.enableGesture(false)                                  // disable gesture engine, (enabled) → void

    printf("status: 0x%02X%n", apds.status())                  // read STATUS register, () → int
    println("is_als_valid: " + apds.isAlsValid())              // check ALS data valid, () → boolean
    println("is_proximity_valid: " + apds.isProximityValid())  // check proximity valid, () → boolean
    println("is_als_saturated: " + apds.isAlsSaturated())      // check ALS saturated, () → boolean
    println("is_proximity_saturated: " + apds.isProximitySaturated())  // check proximity saturated, () → boolean

    apds.enableProximity(false)
} finally {
    transport.close()
}
