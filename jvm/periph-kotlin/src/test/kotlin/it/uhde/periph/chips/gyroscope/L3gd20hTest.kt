package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.MockConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class L3gd20hTest {

    @Test
    fun fullApi() {
        val mock = MockConnection()
            .expectWriteRead(byteArrayOf(0x0F.toByte()), byteArrayOf(0xD7.toByte()))  // WHO_AM_I
            .expectWrite(byteArrayOf(0x23.toByte(), 0x80.toByte()))  // CTRL_REG4
            .expectWrite(byteArrayOf(0x20.toByte(), 0x0F.toByte()))  // CTRL_REG1
            .expectWriteRead(byteArrayOf(0xA8.toByte()), byteArrayOf(0x10, 0x00, 0x00, 0x00, 0xF0.toByte(), 0xFF.toByte()))  // gyro
            .expectWrite(byteArrayOf(0x20.toByte(), 0x4F.toByte()))  // configure CTRL_REG1
            .expectWrite(byteArrayOf(0x23.toByte(), 0x90.toByte()))  // configure CTRL_REG4
            .expectWriteRead(byteArrayOf(0x23.toByte()), byteArrayOf(0x90.toByte()))  // set_full_scale read
            .expectWrite(byteArrayOf(0x23.toByte(), 0xA0.toByte()))  // set_full_scale write
            .expectWriteRead(byteArrayOf(0x27.toByte()), byteArrayOf(0x08.toByte()))  // data_ready
            .expectWriteRead(byteArrayOf(0x27.toByte()), byteArrayOf(0x08.toByte()))  // status
            .expectWriteRead(byteArrayOf(0x26.toByte()), byteArrayOf(0x80.toByte()))  // temperature
            .expectWriteRead(byteArrayOf(0x20.toByte()), byteArrayOf(0x4F.toByte()))  // power_down read
            .expectWrite(byteArrayOf(0x20.toByte(), 0x47.toByte()))  // power_down write
            .expectWriteRead(byteArrayOf(0x20.toByte()), byteArrayOf(0x47.toByte()))  // wake_up read
            .expectWrite(byteArrayOf(0x20.toByte(), 0x4F.toByte()))  // wake_up write
            .expectWrite(byteArrayOf(0x20.toByte(), 0x08.toByte()))  // sleep
            .expectWriteRead(byteArrayOf(0x20.toByte()), byteArrayOf(0x47.toByte()))  // enable_axes read
            .expectWrite(byteArrayOf(0x20.toByte(), 0x4A.toByte()))  // enable_axes write
            .expectWriteRead(byteArrayOf(0x24.toByte()), byteArrayOf(0x00.toByte()))  // enable_fifo read CTRL_REG5
            .expectWrite(byteArrayOf(0x24.toByte(), 0x40.toByte()))  // enable_fifo write FIFO_EN
            .expectWrite(byteArrayOf(0x2E.toByte(), 0x2A.toByte()))  // enable_fifo write FIFO_CTRL
            .expectWriteRead(byteArrayOf(0x2F.toByte()), byteArrayOf(0x1A.toByte()))  // fifo_samples
            .expectWrite(byteArrayOf(0x21.toByte(), 0x15.toByte()))  // enable_highpass CTRL_REG2
            .expectWriteRead(byteArrayOf(0x24.toByte()), byteArrayOf(0x40.toByte()))  // enable_highpass read CTRL_REG5
            .expectWrite(byteArrayOf(0x24.toByte(), 0x50.toByte()))  // enable_highpass write HPen
            .expectWriteRead(byteArrayOf(0x24.toByte()), byteArrayOf(0x50.toByte()))  // disable_highpass read
            .expectWrite(byteArrayOf(0x24.toByte(), 0x40.toByte()))  // disable_highpass write
            .expectWrite(byteArrayOf(0x30.toByte(), 0x6A.toByte()))  // set_interrupt INT1_CFG
            .expectWriteRead(byteArrayOf(0x22.toByte()), byteArrayOf(0x00.toByte()))  // set_interrupt read CTRL_REG3
            .expectWrite(byteArrayOf(0x22.toByte(), 0x80.toByte()))  // set_interrupt write I1_Int1
            .expectWrite(byteArrayOf(0x32.toByte(), 0x04.toByte()))  // set_threshold XH
            .expectWrite(byteArrayOf(0x33.toByte(), 0xE2.toByte()))  // set_threshold XL
            .expectWrite(byteArrayOf(0x38.toByte(), 0x84.toByte()))  // set_duration
            .expectWriteRead(byteArrayOf(0x31.toByte()), byteArrayOf(0x7F.toByte()))  // read_int_source
            .expectWriteRead(byteArrayOf(0x22.toByte()), byteArrayOf(0x80.toByte()))  // set_data_ready_pin read CTRL_REG3
            .expectWrite(byteArrayOf(0x22.toByte(), 0x88.toByte()))  // set_data_ready_pin write

        val sensor = L3gd20hFull(mock, false)

        val k = Math.PI.toFloat() / 180.0f
        val expectedX = 16.0f * 0.00875f * k
        val expectedZ = -16.0f * 0.00875f * k

        val xyz = sensor.gyro()
        assertEquals(expectedX, xyz[0], 1e-6f)
        assertEquals(0.0f, xyz[1])
        assertEquals(expectedZ, xyz[2])

        sensor.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS)
        assertEquals(500, sensor.fullScale)

        val raw = sensor.gyroRaw()
        assertEquals(-32768, raw[0])
        assertEquals(32767, raw[1])
        assertEquals(0, raw[2])

        assertEquals(-128, sensor.temperature())
        assertTrue(sensor.dataReady())
        assertEquals(0x08, sensor.status())

        sensor.powerDown()
        sensor.wakeUp()
        sensor.sleep()
        sensor.enableAxes(false, true, false)
        sensor.enableFifo(L3gd20hFull.FIFO_FIFO, 10)
        assertEquals(26, sensor.fifoSamples())
        sensor.configureHpFilter(L3gd20hFull.HPM_REFERENCE, 5)
        sensor.enableHpFilter(false)
        sensor.setInterrupt(true, false, true, false, true, false, false, true)
        sensor.setThreshold('x', 87.5f)
        sensor.setDuration(4, true)
        assertEquals(0x7F, sensor.readIntSource())
        sensor.setDataReadyPin(true)

        mock.verify()
    }
}