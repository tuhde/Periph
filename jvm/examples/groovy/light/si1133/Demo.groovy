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
    sensor.init()                                                                                   // Check PART_ID, reset, UV + white channels, () → void

    // --- Identity ---
    // PART_ID 0x33 identifies the Si1133; HW_ID and REV_ID give silicon and
    // product revision. RESPONSE0 shows the sequencer state and command counter.
    def id = sensor.readId()                                                                        // Identity registers, () → Id {partId, hwId, revId}
    System.out.printf("PART_ID 0x%02X  HW_ID 0x%02X  REV_ID 0x%02X%n", id.partId(), id.hwId(), id.revId())
    System.out.println("response " + sensor.readResponse())                                         // RESPONSE0 decoded, () → Response

    // --- UV and daylight monitor: 20 s at 1 Hz ---
    // The counts are raw ADC values; a UV index needs Silicon Labs' calibration
    // for the product's window, so watch the trend.
    for (int i = 0; i < 20; i++) {
        def m = sensor.read()                                                                       // Forced measurement, () → Reading {uv counts, white counts}
        System.out.printf("%2d s  UV %8d  white %8d%n", i, m.uv(), m.white())
        Thread.sleep(1000)
    }

    // --- Gain settings on a constant light level ---
    // HW_GAIN doubles the integration time per step, SW_GAIN accumulates 2^n
    // samples, HIGH_SIGNAL divides the sensitivity by 14.5 for direct sunlight.
    sensor.enableChannels(0x04)                                                                     // CHAN_LIST, (mask) → void
    def gains = [[2, 0, 0], [6, 0, 0], [6, 2, 0], [6, 0, 1]]
    for (List<Integer> g : gains) {
        sensor.configureChannel(2, Si1133Full.MUX_WHITE, g[0], g[1], 0, g[2] != 0, true, 0)         // Channel setup, (channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24, postshift) → void
        int value = sensor.readChannels()[0]                                                        // Forced read, () → int[]
        System.out.printf("hwGain %d swGain %d highSignal %b -> %d%n", g[0], g[1], g[2] != 0, value)
    }

    // --- IR photodiodes ---
    def names = ["small", "medium", "large"]
    def muxes = [Si1133Full.MUX_SMALL_IR, Si1133Full.MUX_MEDIUM_IR, Si1133Full.MUX_LARGE_IR]
    for (int i = 0; i < 3; i++) {
        System.out.printf("IR %-6s %d%n", names[i], sensor.readIr(muxes[i]))                        // IR photodiode once, (photodiode) → int counts
    }
    System.out.printf("UV %d, white %d%n", sensor.readUv(), sensor.readWhite())                     // Scratch-channel reads, () → int counts

    // --- Autonomous mode with a UV threshold ---
    // Channels 0 and 1 run every second; channel 0 only interrupts when crossing
    // THRESHOLD0, channel 1 on every result.
    sensor.configureChannel(0, Si1133Full.MUX_UV, 9)                                                // Channel setup, (channel, adcmux, hwGain) → void
    sensor.enableChannels(0x03)                                                                     // CHAN_LIST, (mask) → void
    sensor.setThreshold(0, 500)                                                                     // Threshold value, (index, value) → void
    sensor.setChannelThreshold(0, 1)                                                                // Threshold select, (channel, index) → void
    sensor.setChannelCounter(1, 1)                                                                  // Counter for a channel, (channel, index) → void
    sensor.disableInterrupt(Si1133Full.SOURCE_CHANNEL1)                                             // IRQ_ENABLE clear, (source) → void
    sensor.enableInterrupt(Si1133Full.SOURCE_CHANNEL0)                                              // IRQ_ENABLE set, (source) → void
    sensor.onInterrupt({ int status ->                                                              // Subscribe, (callback) → void
        try {
            System.out.printf("interrupt 0x%02X values %s%n", status, Arrays.toString(sensor.readChannels(false)))
        } catch (java.io.IOException e) {
            System.out.println("read failed: " + e.getMessage())
        }
    } as IntConsumer)
    sensor.startAutonomous(0.1, 10, 10, 10)                                                         // Autonomous mode, (rateS, counters...) → void
    Thread.sleep(5000)
    sensor.offInterrupt()                                                                           // Unsubscribe, () → void
    sensor.pause()                                                                                  // PAUSE, () → void
    sensor.enableInterrupt(Si1133Full.SOURCE_CHANNEL1)                                              // IRQ_ENABLE set, (source) → void

    // --- Burst mode: four readings in a row ---
    sensor.setBurst(4)                                                                              // Burst mode, (count) → void
    sensor.force()                                                                                  // FORCE without waiting, () → void
    Thread.sleep(500)
    System.out.printf("burst ready 0x%02X%n", sensor.dataReady())                                   // IRQ_STATUS mask, () → int
    System.out.println("burst values " + Arrays.toString(sensor.readChannels(false)))               // Decode current results, (force=false) → int[]
    sensor.setBurst(0)

    // --- Parameter table ---
    sensor.writeParameter(0x1E, 7)                                                                  // PARAM_SET, (address, value) → void
    int chan = sensor.readParameter(0x01)                                                           // PARAM_QUERY, (address) → int
    int rate = (sensor.readParameter(0x1A) << 8) | sensor.readParameter(0x1B)
    System.out.printf("CHAN_LIST 0x%02X  MEASRATE %d  MEASCOUNT2 %d%n", chan, rate, sensor.readParameter(0x1E))
    System.out.println("poll " + sensor.pollInterrupt())                                            // IRQ_STATUS mask, () → int

    // --- Finish ---
    // setI2cAddress would move the chip to another address until the next
    // reset; the demo keeps 0x55 and only shows the call.
    // sensor.setI2cAddress(0x56)                                                          // Change address, (address) → void
    sensor.reset()                                                                                  // RESET_SW + defaults, () → void
    System.out.println("after reset " + sensor.read())                                              // Forced measurement, () → Reading
} finally {
    connection.close()
}
