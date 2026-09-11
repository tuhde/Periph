///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps33hwFull

def connection = new I2CConnection(1, 0x5C)              // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
try {
    def sensor = new Lps33hwFull(connection)                    // construct driver, verifies chip ID, (connection) → Lps33hwFull

    int st = sensor.status()                                    // read STATUS register, () → int
                                                                 // bit 0 = P_DA, bit 1 = T_DA, bit 4 = P_OR, bit 5 = T_OR
    int intsrc = sensor.interruptStatus()                       // read INT_SOURCE register, () → int
                                                                 // bit 0 = PH, bit 1 = PL, bit 2 = IA, bit 7 = BOOT_STATUS

    sensor.configure(                                           // configure CTRL_REG1+RES_CONF, (odr 0–5, bdu, enLpfp, lpfpCfg, lcEn, sim) → void
        Lps33hwFull.ODR_10_HZ, true, true,                     // 10 Hz ODR, BDU=1, EN_LPFP=1
        Lps33hwFull.LPFP_BW_ODR_20, false, false)              // LPF bandwidth ODR/20, LC_EN off, 4-wire SPI

    def oneShot = sensor.oneShot()                             // trigger one-shot measurement, () → double[2] (Pa, °C)
                                                                 // writes ONE_SHOT in CTRL_REG2, polls P_DA+T_DA, returns the pair
    double p = sensor.pressure()                                // read pressure via burst read, () → double Pa
    double t = sensor.temperature()                             // read temperature via burst read, () → double °C

    sensor.setPressureOffset(0.5)                               // set pressure offset, (offsetHPa) → void
                                                                 // writes RPDS_L/RPDS_H (1 LSB = 1/16 hPa)
    sensor.setAutozero()                                        // set AUTOZERO, () → void
                                                                 // stores the next pressure sample in REF_P
    sensor.clearAutozero()                                      // clear AUTOZERO, () → void
    sensor.setAutorifp()                                        // set AUTORIFP, () → void
                                                                 // stores the next pressure sample in RPDS (persists across resets)
    sensor.clearAutorifp()                                      // clear AUTORIFP, () → void

    sensor.configureInterrupt(true, false, false, false,       // configure INT_DRDY routing, (drdy, fFth, fOvr, fFss5, intS, activeLow, openDrain) → void
        Lps33hwFull.INT_S_DATA_SIGNALS, false, false)           // route DRDY only, push-pull, active high

    sensor.configurePressureInterrupt(true, true, 5.0, true)   // configure pressure threshold interrupt, (highEn, lowEn, thresholdHPa, latch) → void
                                                                 // sets THS_P and DIFF_EN/LIR/PHE/PLE bits

    sensor.enableFifo(Lps33hwFull.FIFO_MODE_STREAM, 16)         // enable FIFO, (mode 0–7 not 5, watermark 0–31) → void
                                                                 // sets FIFO_CTRL and FIFO_EN in CTRL_REG2
    int fst = sensor.fifoStatus()                               // read FIFO_STATUS, () → int
                                                                 // bit 7 = FTH_FIFO, bit 6 = OVR, bits [5:0] = FSS count
    sensor.disableFifo()                                        // disable FIFO, () → void

    sensor.resetLpf()                                           // reset LPF, () → void
                                                                 // reads LPFP_RES to flush transitory state

    sensor.reset()                                              // software reset, () → void
                                                                 // SWRESET in CTRL_REG2, waits for self-clear, restores defaults
    sensor.reboot()                                             // reboot from Flash, () → void
                                                                 // sets BOOT in CTRL_REG2, waits for self-clear

    printf("status=0x%02X  int_source=0x%02X  fifo_status=0x%02X%n", st, intsrc, fst)
    printf("one_shot: %.1f Pa / %.2f C, then: %.1f Pa / %.2f C%n",
        oneShot[0], oneShot[1], p, t)
} finally {
    connection.close()
}