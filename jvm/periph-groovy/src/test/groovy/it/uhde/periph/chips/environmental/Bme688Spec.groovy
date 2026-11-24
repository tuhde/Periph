package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bme688Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Bme688Minimal.REG_ID, 0x61)
        // Calibration block 1 (23 bytes from 0x8A). No published worked example
        // exists for BME688 - these are self-consistent, hand-derived values
        // used to check every language's translation against the same formula.
        connection.setRegister(Bme688Minimal.REG_CALIB_BLOCK1,
                0x43, 0x67, 0x03, 0x00, 0x7D, 0x8E, 0x43, 0xD6, 0x58, 0x00, 0x27,
                0x0B, 0x8C, 0x00, 0x0F, 0xF9, 0x00, 0x00, 0xF8, 0xC6, 0x70, 0x17, 0x1E)
        // Calibration block 2 (14 bytes from 0xE1).
        connection.setRegister(Bme688Minimal.REG_CALIB_BLOCK2,
                0x2B, 0xC8, 0x25, 0x00, 0x2D, 0x14, 0x78, 0x9C, 0x90, 0x65, 0x0C, 0xE5, 0xE2, 0x1E)
        // Single-byte calibration: resHeatVal=50, resHeatRange=2.
        connection.setRegister(Bme688Minimal.REG_RES_HEAT_VAL, 0x32)
        connection.setRegister(Bme688Minimal.REG_RES_HEAT_RANGE, 0x20)
        // Field 0 (17 bytes from 0x1D): new_data set, pressAdc=415148, tempAdc=419888,
        // humAdc=20000, gasAdc=400, gasRange=5, gasValid=1, heatStab=1
        // (gas at 0x2C/0x2D, not 0x2A/0x2B as on the BME680).
        def field0 = [0x80, 0x00, 0x65, 0x5A, 0xC0, 0x66, 0x83, 0x00, 0x4E, 0x20,
                      0x00, 0x00, 0x00, 0x00, 0x00, 0x64, 0x35] as int[]
        connection.setRegister(Bme688Minimal.REG_DATA, field0)

        def expectedT = 1.23d
        def expectedP = 969.4d
        def expectedH = 39.826d
        def expectedGas = 2178700.0d

        when:
        def sensor = new Bme688Full(connection)

        then:
        connection.registers().get(Bme688Minimal.REG_CTRL_HUM) == 0x01
        connection.registers().get(Bme688Minimal.REG_CTRL_MEAS) == ((1 << 5) | (1 << 2) | 0)
        connection.registers().get(Bme688Minimal.REG_CONFIG) == 0x00
        connection.registers().get(Bme688Minimal.REG_RES_HEAT_0) == 0x52
        connection.registers().get(Bme688Minimal.REG_GAS_WAIT_0) == 0x65
        connection.registers().get(Bme688Minimal.REG_CTRL_GAS_1) == 0x20

        when:
        sensor.setHeater(300, 200)

        then:
        connection.registers().get(Bme688Minimal.REG_RES_HEAT_0) == 0x4E
        connection.registers().get(Bme688Minimal.REG_GAS_WAIT_0) == 0x72

        when:
        sensor.setHeaterProfile(4, 280, 50)

        then:
        connection.registers().get(Bme688Minimal.REG_RES_HEAT_0 + 4) == 0x49
        connection.registers().get(Bme688Minimal.REG_GAS_WAIT_0 + 4) == 0x32

        expect:
        Math.abs(sensor.temperature() - expectedT) < 0.01
        Math.abs(sensor.pressure() - expectedP) < 0.1
        Math.abs(sensor.humidity() - expectedH) < 0.01
        Math.abs(sensor.gasResistance() - expectedGas) < 1.0

        when:
        int lastCtrlMeas = connection.registers().get(Bme688Minimal.REG_CTRL_MEAS)

        then:
        (lastCtrlMeas & 0x03) == 1

        when:
        sensor.configure(2, 3, 1, 0, 3)

        then:
        connection.registers().get(Bme688Minimal.REG_CTRL_HUM) == 1
        connection.registers().get(Bme688Minimal.REG_CONFIG) == (3 << 2)
        connection.registers().get(Bme688Minimal.REG_CTRL_MEAS) == ((2 << 5) | (3 << 2) | 0)

        when:
        sensor.setOversampling(3, 4, 2)

        then:
        connection.registers().get(Bme688Minimal.REG_CTRL_HUM) == 2
        connection.registers().get(Bme688Minimal.REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 0)

        when:
        sensor.setFilter(5)

        then:
        connection.registers().get(Bme688Minimal.REG_CONFIG) == (5 << 2)

        when:
        sensor.selectHeaterProfile(2)

        then:
        connection.registers().get(Bme688Minimal.REG_CTRL_GAS_1) == (0x20 | 2)

        when:
        sensor.setGasEnabled(false)

        then:
        connection.registers().get(Bme688Minimal.REG_CTRL_GAS_1) == 2

        when:
        sensor.setGasEnabled(true)

        then:
        connection.registers().get(Bme688Minimal.REG_CTRL_GAS_1) == (0x20 | 2)

        when:
        sensor.setHeaterOff(true)

        then:
        connection.registers().get(Bme688Minimal.REG_CTRL_GAS_0) == 0x08

        when:
        sensor.setHeaterOff(false)

        then:
        connection.registers().get(Bme688Minimal.REG_CTRL_GAS_0) == 0x00

        when:
        def all = sensor.readAll()

        then:
        Math.abs(all[0] - expectedT) < 0.01
        Math.abs(all[1] - expectedP) < 0.1
        Math.abs(all[2] - expectedH) < 0.01
        Math.abs(all[3] - expectedGas) < 1.0
        sensor.gasValid()
        sensor.heaterStable()

        expect:
        sensor.chipId() == 0x61

        when:
        connection.setRegister(Bme688Minimal.REG_VARIANT_ID, 0x01)

        then:
        sensor.variantId() == 0x01

        when:
        sensor.startParallel([320, 250] as int[], [1, 2] as int[], 100d)

        then:
        connection.registers().get(Bme688Minimal.REG_RES_HEAT_0) == 0x52
        connection.registers().get(Bme688Minimal.REG_GAS_WAIT_0) == 1
        connection.registers().get(Bme688Minimal.REG_GAS_WAIT_0 + 1) == 2
        connection.registers().get(0x6E) == ((1 << 6) | 52)
        connection.registers().get(Bme688Minimal.REG_CTRL_GAS_1) == (0x20 | 2)
        (connection.registers().get(Bme688Minimal.REG_CTRL_MEAS) & 0x03) == 2

        when:
        def field2 = field0.clone() as int[]
        field2[0] = 0x81
        field2[1] = 0x03
        field2[16] = 0x05
        def all3 = (field0 as List) + ([0] * 17) + (field2 as List)
        connection.setRegister(Bme688Minimal.REG_DATA, all3 as int[])
        def fields = sensor.readParallel()

        then:
        fields.size() == 2
        fields[0].step == 0
        fields[0].subIndex == 0
        Math.abs(fields[0].temperature - expectedT) < 0.01
        Math.abs(fields[0].gasResistance - expectedGas) < 1.0
        fields[0].gasValid && fields[0].heaterStable
        fields[1].step == 1
        fields[1].subIndex == 3
        Double.isNaN(fields[1].gasResistance)

        when:
        sensor.stopParallel()

        then:
        (connection.registers().get(Bme688Minimal.REG_CTRL_MEAS) & 0x03) == 0

        when:
        sensor.reset()
        def sawReset = connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == Bme688Minimal.REG_RESET && (it[1] & 0xFF) == 0xB6
        }

        then:
        sawReset
        connection.registers().get(Bme688Minimal.REG_CTRL_HUM) == 2
        connection.registers().get(Bme688Minimal.REG_CONFIG) == (5 << 2)
        connection.registers().get(Bme688Minimal.REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 0)
        connection.registers().get(Bme688Minimal.REG_CTRL_GAS_1) == (0x20 | 2)
        sensor.status() == 0x80
    }
}
