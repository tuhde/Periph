///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps33hwFull

fun main() {
    I2CConnection(1, 0x5C).use { connection ->              // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
        val sensor = Lps33hwFull(connection)                        // construct driver, verifies chip ID, (connection) → Lps33hwFull

        val st = sensor.status()                                    // read STATUS register, () → Int
                                                                     // bit 0 = P_DA, bit 1 = T_DA, bit 4 = P_OR, bit 5 = T_OR
        val intsrc = sensor.interruptStatus()                       // read INT_SOURCE register, () → Int
                                                                     // bit 0 = PH, bit 1 = PL, bit 2 = IA, bit 7 = BOOT_STATUS

        sensor.configure(                                           // configure CTRL_REG1+RES_CONF, (odr 0–5, bdu, enLpfp, lpfpCfg, lcEn, sim) → Unit
            Lps33hwFull.ODR_10_HZ, true, true,                      // 10 Hz ODR, BDU=1, EN_LPFP=1
            Lps33hwFull.LPFP_BW_ODR_20, false, false)               // LPF bandwidth ODR/20, LC_EN off, 4-wire SPI

        val (pOs, tOs) = sensor.oneShot()                           // trigger one-shot measurement, () → Pair(Double Pa, Double °C)
                                                                     // writes ONE_SHOT in CTRL_REG2, polls P_DA+T_DA, returns the pair
        val p = sensor.pressure()                                   // read pressure via burst read, () → Double Pa
        val t = sensor.temperature()                                // read temperature via burst read, () → Double °C

        sensor.setPressureOffset(0.5)                               // set pressure offset, (offsetHPa) → Unit
                                                                     // writes RPDS_L/RPDS_H (1 LSB = 1/16 hPa)
        sensor.setAutozero()                                        // set AUTOZERO, () → Unit
                                                                     // stores the next pressure sample in REF_P
        sensor.clearAutozero()                                      // clear AUTOZERO, () → Unit
        sensor.setAutorifp()                                        // set AUTORIFP, () → Unit
                                                                     // stores the next pressure sample in RPDS (persists across resets)
        sensor.clearAutorifp()                                      // clear AUTORIFP, () → Unit

        sensor.configureInterrupt(true, false, false, false,       // configure INT_DRDY routing, (drdy, fFth, fOvr, fFss5, intS, activeLow, openDrain) → Unit
            Lps33hwFull.INT_S_DATA_SIGNALS, false, false)           // route DRDY only, push-pull, active high

        sensor.configurePressureInterrupt(true, true, 5.0, true)   // configure pressure threshold interrupt, (highEn, lowEn, thresholdHPa, latch) → Unit
                                                                     // sets THS_P and DIFF_EN/LIR/PHE/PLE bits

        sensor.enableFifo(Lps33hwFull.FIFO_MODE_STREAM, 16)         // enable FIFO, (mode 0–7 not 5, watermark 0–31) → Unit
                                                                     // sets FIFO_CTRL and FIFO_EN in CTRL_REG2
        val fst = sensor.fifoStatus()                               // read FIFO_STATUS, () → Int
                                                                     // bit 7 = FTH_FIFO, bit 6 = OVR, bits [5:0] = FSS count
        sensor.disableFifo()                                        // disable FIFO, () → Unit

        sensor.resetLpf()                                           // reset LPF, () → Unit
                                                                     // reads LPFP_RES to flush transitory state

        sensor.reset()                                              // software reset, () → Unit
                                                                     // SWRESET in CTRL_REG2, waits for self-clear, restores defaults
        sensor.reboot()                                             // reboot from Flash, () → Unit
                                                                     // sets BOOT in CTRL_REG2, waits for self-clear

        println("status=0x%02X  int_source=0x%02X  fifo_status=0x%02X".format(st, intsrc, fst))
        println("one_shot: %.1f Pa / %.2f C, then: %.1f Pa / %.2f C".format(pOs, tOs, p, t))
    }
}