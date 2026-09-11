package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class Lps33hwTest {

    private fun preloadDefaults(connection: MockConnection) {
        connection.setRegister(Lps33hwMinimal.REG_WHO_AM_I, 0xB1)
    }

    @Test
    fun fullApi() {
        val connection = MockConnection()
        preloadDefaults(connection)

        // Set up STATUS to immediately indicate both P_DA and T_DA so the
        // driver doesn't loop. Burst returns raw_pressure = 4096 (= 100 Pa)
        // and raw_temperature = 2500 (= 25 °C).
        connection.setRegister(Lps33hwMinimal.REG_STATUS, 0x03)
        connection.setRegister(Lps33hwMinimal.REG_PRESS_XL,
            0x00, 0x10, 0x00, 0xC4, 0x09)

        // RES_CONF default (used by configure())
        connection.setRegister(Lps33hwMinimal.REG_RES_CONF, 0x00)

        val sensor = Lps33hwFull(connection)

        // pressure() / temperature()
        val p = sensor.pressure()
        assertEquals(100.0, p, 1e-3, "pressure = 4096 raw = 100 Pa")
        val t = sensor.temperature()
        assertEquals(25.0, t, 1e-3, "temperature = 2500 raw = 25 °C")

        // status()
        connection.setRegister(Lps33hwMinimal.REG_STATUS, 0x03)
        assertEquals(0x03, sensor.status())

        // interruptStatus()
        connection.setRegister(Lps33hwMinimal.REG_INT_SOURCE, 0x04)
        assertEquals(0x04, sensor.interruptStatus())

        // configure(odr=2, bdu=true, enLpfp=true, lpfpCfg=1, lcEn=false, sim=false):
        // ctrl1 = (2<<4)|(1<<3)|(1<<2)|(1<<1)|0 = 0x2E
        sensor.configure(2, true, true, 1, false, false)
        val ctrl1Write = connection.writes().last {
            it.size == 2 && (it[0].toInt() and 0xFF) == Lps33hwMinimal.REG_CTRL_REG1
        }
        assertEquals(0x2E.toByte(), ctrl1Write[1])

        // setPressureOffset(offsetHPa=16.0):
        // raw = 16 * 16 = 256 -> RPDS_L=0x00, RPDS_H=0x01
        sensor.setPressureOffset(16.0)
        val rpdsL = connection.writes().last {
            it.size == 2 && (it[0].toInt() and 0xFF) == Lps33hwMinimal.REG_RPDS_L
        }
        val rpdsH = connection.writes().last {
            it.size == 2 && (it[0].toInt() and 0xFF) == Lps33hwMinimal.REG_RPDS_H
        }
        assertEquals(0x00.toByte(), rpdsL[1])
        assertEquals(0x01.toByte(), rpdsH[1])

        // setAutozero() / clearAutozero() / setAutorifp() / clearAutorifp()
        sensor.setAutozero()
        sensor.clearAutozero()
        sensor.setAutorifp()
        sensor.clearAutorifp()

        // configureInterrupt(all true, intS=3): ctrl3 = 0xFF
        sensor.configureInterrupt(true, true, true, true, 3, true, true)
        val ctrl3Write = connection.writes().last {
            it.size == 2 && (it[0].toInt() and 0xFF) == Lps33hwMinimal.REG_CTRL_REG3
        }
        assertEquals(0xFF.toByte(), ctrl3Write[1])

        // enableFifo(mode=1, watermark=16): fifo_ctrl = (1<<5)|16 = 0x30
        sensor.enableFifo(1, 16)
        val fifoCtrlWrite = connection.writes().last {
            it.size == 2 && (it[0].toInt() and 0xFF) == Lps33hwMinimal.REG_FIFO_CTRL
        }
        assertEquals(0x30.toByte(), fifoCtrlWrite[1])

        // fifoStatus() returns the preloaded value
        connection.setRegister(Lps33hwMinimal.REG_FIFO_STATUS, 0x80)
        assertEquals(0x80, sensor.fifoStatus())

        // disableFifo() clears FIFO_EN and resets FIFO_CTRL
        sensor.disableFifo()

        // resetLpf(): read LPFP_RES
        connection.setRegister(Lps33hwMinimal.REG_LPFP_RES, 0x00)
        sensor.resetLpf()

        // reset(): writes SWRESET, polls, restores defaults
        sensor.reset()

        // reboot(): writes BOOT, polls INT_SOURCE
        sensor.reboot()
    }

    @Test
    fun wrongChipIdThrows() {
        val connection = MockConnection()
        connection.setRegister(Lps33hwMinimal.REG_WHO_AM_I, 0x00)  // wrong
        assertThrows(IOException::class.java) { Lps33hwMinimal(connection) }
    }
}