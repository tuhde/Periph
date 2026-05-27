package it.uhde.periph.chips.gas

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * ENS160 digital multi-gas sensor — minimal interface.
 *
 * Provides calibrated air quality readings (AQI, TVOC, eCO2) with no
 * configuration required beyond the transport. The sensor performs automatic
 * baseline correction and on-chip signal processing.
 *
 * Default: STANDARD mode (gas sensing active), polling only, no external
 * T/RH compensation.
 */
open class Ens160Minimal(protected val transport: Transport) {

    companion object {
        const val REG_PART_ID       = 0x00
        const val REG_OPMODE        = 0x10
        const val REG_CONFIG        = 0x11
        const val REG_COMMAND       = 0x12
        const val REG_TEMP_IN       = 0x13
        const val REG_RH_IN         = 0x15
        const val REG_DEVICE_STATUS = 0x20
        const val REG_DATA_AQI      = 0x21
        const val REG_DATA_TVOC     = 0x22
        const val REG_DATA_ECO2     = 0x24
        const val REG_DATA_T        = 0x30
        const val REG_DATA_RH       = 0x32
        const val REG_GPR_READ      = 0x48

        const val OPMODE_DEEP_SLEEP = 0x00
        const val OPMODE_IDLE       = 0x01
        const val OPMODE_STANDARD   = 0x02

        const val PART_ID_EXPECTED  = 0x0160
    }

    init {
        writeReg(REG_OPMODE, OPMODE_IDLE)
        Thread.sleep(1)
        val partId = readRegLE16(REG_PART_ID)
        if (partId != PART_ID_EXPECTED) {
            throw IOException("ENS160 not found: expected PART_ID 0x0160, got 0x${partId.toString(16)}")
        }
        writeReg(REG_OPMODE, OPMODE_STANDARD)
    }

    protected fun writeReg(reg: Int, value: Int) {
        transport.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    protected fun writeRegLE16(reg: Int, value: Int) {
        transport.write(byteArrayOf(reg.toByte(), (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))
    }

    protected fun readReg(reg: Int, n: Int): ByteArray {
        return transport.writeRead(byteArrayOf(reg.toByte()), n)
    }

    protected fun readRegLE16(reg: Int): Int {
        val data = readReg(reg, 2)
        return (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
    }

    protected fun readDeviceStatus(): Int {
        val data = readReg(REG_DEVICE_STATUS, 1)
        return data[0].toInt() and 0xFF
    }

    protected fun waitForNewData(timeoutMs: Int): Int {
        val start = System.currentTimeMillis()
        while (true) {
            val status = readDeviceStatus()
            if ((status and 0x02) != 0) {
                return status
            }
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw IOException("ENS160: NEWDAT not set within $timeoutMs ms")
            }
            Thread.sleep(10)
        }
    }

    /**
     * Read the VALIDITY_FLAG from DEVICE_STATUS.
     *
     * @return Validity flag (0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output).
     */
    fun status(): Int {
        val status = readDeviceStatus()
        return (status shr 2) and 0x03
    }

    /**
     * Read calibrated air quality values.
     *
     * Polls until NEWDAT is set, then checks VALIDITY_FLAG. Only returns
     * data when validity is 0 (OK). Reads AQI, TVOC, and eCO2 in a single
     * burst to ensure consistency.
     *
     * @return DoubleArray: [aqi (1–5), tvocPpb, eco2Ppm].
     */
    fun readAirQuality(): DoubleArray {
        val status = waitForNewData(5000)
        val validity = (status shr 2) and 0x03
        if (validity != 0) {
            throw IOException("ENS160: data not valid (VALIDITY_FLAG=$validity)")
        }
        val data = readReg(REG_DATA_AQI, 5)
        val aqi = data[0].toInt() and 0x07
        val tvocPpb = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val eco2Ppm = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
        return doubleArrayOf(aqi.toDouble(), tvocPpb.toDouble(), eco2Ppm.toDouble())
    }
}
