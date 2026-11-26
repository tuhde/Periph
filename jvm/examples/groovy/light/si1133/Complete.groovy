///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Si1133Full

import java.util.function.IntConsumer

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x55").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Si1133Full(connection)                                                         // Create Si1133 driver, (connection) → Si1133Full
    // Constructor stores the connection only; no bus traffic yet.
    sensor.init()                                                                                   // Check PART_ID, reset, UV + white channels, () → void
    // Waits 25 ms, verifies PART_ID 0x33, RESET_SW, channel 0 = UV, channel 1 = white, 24-bit outputs.

    System.out.println(sensor.readId())                                                             // Identity registers, () → Id {partId, hwId, revId}
    // PART_ID is 0x33; HW_ID bits 4:0 are 0x03 for the Si1133-AA00.
    System.out.println(sensor.readResponse())                                                       // RESPONSE0 decoded, () → Response {state, error, counter}
    // State bits RUNNING/SUSPEND/SLEEP, the CMD_ERR flag and the 4-bit command counter.

    System.out.println(sensor.read())                                                               // Forced measurement, () → Reading {uv counts, white counts}
    // FORCE, wait for both channels in IRQ_STATUS, decode two signed 24-bit counts.
    System.out.println(sensor.readUv())                                                             // UV photodiode once, () → int counts
    // Uses channel 5 as a scratch channel and restores CHAN_LIST afterwards.
    System.out.println(sensor.readWhite())                                                          // White photodiode once, () → int counts
    // Same scratch channel, HW_GAIN 7.
    System.out.println(sensor.readIr(Si1133Full.MUX_LARGE_IR))                                      // IR photodiode once, (photodiode) → int counts
    // Small, medium or large IR photodiode; readIr() uses the small one.

    sensor.configureChannel(2, Si1133Full.MUX_LARGE_WHITE, 4, 1, 0, false, false, 0)                // Channel setup, (channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24, postshift) → void
    // Writes ADCCONFIG2, ADCSENS2 and ADCPOST2; this channel reports 16-bit unsigned counts.
    sensor.enableChannels(0x07)                                                                     // CHAN_LIST, (mask) → void
    // Channels 0, 1 and 2 now run on every FORCE.
    System.out.println(Arrays.toString(sensor.readChannels()))                                      // Forced read of all channels, () → int[]
    // Results in channel order: 24-bit signed for channels 0/1, 16-bit for channel 2.
    sensor.force()                                                                                  // FORCE without waiting, () → void
    // Starts a measurement; the results arrive in HOSTOUT when IRQ_STATUS flags the channels.
    Thread.sleep(100)
    System.out.println(sensor.dataReady())                                                          // IRQ_STATUS mask, () → int
    // Bit c set when channel c finished; this read clears the flags.
    System.out.println(Arrays.toString(sensor.readChannels(false)))                                 // Decode current results, (force=false) → int[]
    // No new measurement, only HOSTOUT decoded.

    sensor.setThreshold(0, 2000)                                                                    // Threshold value, (index, value) → void
    // THRESHOLD0 = 2000 counts (16-bit).
    sensor.setChannelThreshold(0, 1)                                                                // Threshold select, (channel, index) → void
    // Channel 0 interrupts only when crossing THRESHOLD0 (index 1); 0 turns it off.
    sensor.setChannelThreshold(0, 0)
    sensor.setChannelCounter(2, 2)                                                                  // Counter for a channel, (channel, index) → void
    // Channel 2 uses MEASCOUNT1 in autonomous mode; 0 keeps a channel forced-only.
    sensor.startAutonomous(0.1, 10, 5, 1)                                                           // Autonomous mode, (rateS, counters...) → void
    // MEASRATE = 0.1 s; channels 0/1 run every 1 s (MEASCOUNT0 = 10), channel 2 every 0.5 s.
    Thread.sleep(1500)
    System.out.println(sensor.pollInterrupt())                                                      // IRQ_STATUS mask, () → int
    // Same as dataReady; also releases the INT pin.
    sensor.pause()                                                                                  // PAUSE, () → void
    // Stops the autonomous measurements.
    sensor.setBurst(4)                                                                              // Burst mode, (count) → void
    // Four readings per burst at MEASRATE; 0 disables burst mode.
    sensor.setBurst(0)

    sensor.writeParameter(0x1E, 3)                                                                  // PARAM_SET, (address, value) → void
    // Direct parameter-table access: MEASCOUNT2 = 3.
    System.out.println(sensor.readParameter(0x1E))                                                  // PARAM_QUERY, (address) → int
    // Reads it back through RESPONSE1.

    sensor.disableInterrupt(Si1133Full.SOURCE_CHANNEL2)                                             // IRQ_ENABLE clear, (source) → void
    // Channel 2 no longer drives INT.
    sensor.enableInterrupt(Si1133Full.SOURCE_CHANNEL2)                                              // IRQ_ENABLE set, (source) → void
    // Channel 2 drives INT again.
    sensor.onInterrupt({ int status -> System.out.printf("irq 0x%02X%n", status) } as IntConsumer)  // Subscribe, (callback) → void
    // INT falling edge when the connection has a pin, otherwise a 10 ms IRQ_STATUS poll.
    sensor.readChannels()
    Thread.sleep(100)
    sensor.offInterrupt()                                                                           // Unsubscribe, () → void
    // Stops the edge handler or polling thread.

    sensor.reset()                                                                                  // RESET_SW + defaults, () → void
    // Parameter table back to power-up values, then channel 0 = UV, channel 1 = white again.
    // sensor.setI2cAddress(0x56)                                                          // Change address, (address) → void
    // Volatile; the chip answers on the new address at once, so a new connection is needed.
} finally {
    connection.close()
}
