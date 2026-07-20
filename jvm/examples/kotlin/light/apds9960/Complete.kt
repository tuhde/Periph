///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.light.Apds9960Full

fun main() {
    I2CTransport(1, 0x39).use { transport ->                 // open I²C bus 1, device 0x39, (bus, address) → I2CTransport
        val apds = Apds9960Full(transport)                         // construct driver, (transport) → Apds9960Full

        println("chip_id: 0x%02X".format(apds.chipId()))           // read device ID, () → Int

        val rgbc = apds.color()                                    // read all RGBC channels, () → IntArray [clear, red, green, blue]
                                                                   // burst read 0x94-0x9B latches all channels atomically
        println("C=${rgbc[0]} R=${rgbc[1]} G=${rgbc[2]} B=${rgbc[3]}")
        println("clear: ${apds.colorClear()}")                     // read clear channel, () → Int 0-65535
        println("red: ${apds.colorRed()}")                         // read red channel, () → Int 0-65535
        println("green: ${apds.colorGreen()}")                     // read green channel, () → Int 0-65535
        println("blue: ${apds.colorBlue()}")                       // read blue channel, () → Int 0-65535

        apds.configureAls(0xB6, 1)                                 // configure ALS, (atime 0-255, again 0-3) → Unit
                                                                   // sets integration time and gain for the ALS/color engine
        apds.configureWait(0xFF, false)                            // configure wait, (wtime 0-255, wlong=false) → Unit
                                                                   // sets idle period between measurement cycles
        apds.enableWait(true)                                      // enable wait engine, (enabled) → Unit

        apds.enableProximity(true)                                 // enable proximity engine, (enabled) → Unit
        apds.configureProximityLed(0, 0, 0, 1)                     // configure proximity LED, (ldrive 0-3, pgain 0-3, ppulse 0-63, pplen 0-3) → Unit
                                                                   // sets LED drive strength, gain, pulse count and length
        apds.setLedBoost(0)                                        // set LED boost, (boost 0-3) → Unit
                                                                   // multiplies LED current: 0=100%, 1=150%, 2=200%, 3=300%
        println("proximity: ${apds.proximity()}")                  // read proximity count, () → Int 0-255

        apds.alsThreshold(100, 60000)                              // set ALS thresholds, (low 0-65535, high 0-65535) → Unit
        apds.proximityThreshold(10, 200)                           // set proximity thresholds, (low 0-255, high 0-255) → Unit
        apds.setPersistence(0, 1)                                  // set persistence, (ppers 0-15, apers 0-15) → Unit

        apds.enableAlsInterrupt(true)                              // enable ALS interrupt, (enabled) → Unit
        apds.enableProximityInterrupt(true)                        // enable proximity interrupt, (enabled) → Unit
        apds.clearAlsInterrupt()                                   // clear ALS interrupt, () → Unit
        apds.clearProximityInterrupt()                             // clear proximity interrupt, () → Unit
        apds.clearAllInterrupts()                                  // clear all interrupts, () → Unit

        apds.setProximityOffset(10, -5)                            // set proximity offset, (ur -127..127, dl -127..127) → Unit
                                                                   // sign-magnitude encoding compensates for optical crosstalk
        apds.setProximityMask(false, false, false, false)          // set proximity mask, (u, d, l, r) → Unit

        apds.enableGesture(true)                                   // enable gesture engine, (enabled) → Unit
        apds.configureGesture(1, 0, 0, 1, 1, 50, 20)              // configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → Unit
                                                                   // sets gain, LED drive, pulse, wait time, entry/exit thresholds
        println("gesture_available: ${apds.gestureAvailable()}")   // check gesture data, () → Boolean
        println("gesture_fifo_level: ${apds.gestureFifoLevel()}")  // read FIFO level, () → Int
        val fifo = apds.readGestureFifo()                          // read gesture FIFO, () → List<IntArray>
        apds.clearGestureFifo()                                    // clear gesture FIFO, () → Unit
        apds.enableGestureInterrupt(false)                         // enable gesture interrupt, (enabled) → Unit
        apds.enableGesture(false)                                  // disable gesture engine, (enabled) → Unit

        println("status: 0x%02X".format(apds.status()))            // read STATUS register, () → Int
        println("is_als_valid: ${apds.isAlsValid()}")              // check ALS data valid, () → Boolean
        println("is_proximity_valid: ${apds.isProximityValid()}")  // check proximity valid, () → Boolean
        println("is_als_saturated: ${apds.isAlsSaturated()}")      // check ALS saturated, () → Boolean
        println("is_proximity_saturated: ${apds.isProximitySaturated()}")  // check proximity saturated, () → Boolean

        apds.enableProximity(false)
    }
}
