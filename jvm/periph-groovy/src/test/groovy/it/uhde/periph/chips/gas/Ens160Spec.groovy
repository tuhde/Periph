package it.uhde.periph.chips.gas

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ens160Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        // PART_ID read during construction must return 0x0160 (LE: 0x60, 0x01).
        connection.setRegister(Ens160Minimal.REG_PART_ID, 0x60, 0x01)

        when:
        def sensor = new Ens160Full(connection)

        then:
        def opmodeWrites = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Ens160Minimal.REG_OPMODE }
        opmodeWrites.first()[1] == (byte) Ens160Minimal.OPMODE_IDLE
        opmodeWrites.last()[1] == (byte) Ens160Minimal.OPMODE_STANDARD

        when: "DEVICE_STATUS: NEWDAT (bit1) set, VALIDITY_FLAG (bits 3:2) = 0 (OK)"
        connection.setRegister(Ens160Minimal.REG_DEVICE_STATUS, 0x02)

        then:
        sensor.status() == Ens160Full.VALIDITY_OK

        when: "DATA_AQI block (5 bytes): aqi=2, tvocPpb=150 (0x0096 LE), eco2Ppm=500 (0x01F4 LE)"
        connection.setRegister(Ens160Minimal.REG_DATA_AQI, 2, 0x96, 0x00, 0xF4, 0x01)
        def data = sensor.readAirQuality()

        then:
        data == [2.0d, 150.0d, 500.0d] as double[]
        sensor.readTvoc() == 150.0d
        sensor.readEco2() == 500.0d
        sensor.readAqi() == 2
        sensor.readEthanol() == 150.0d

        when:
        sensor.setCompensation(25.0d, 50.0d)
        int tempRaw = Math.round((25.0d + 273.15d) * 64)
        int rhRaw = Math.round(50.0d * 512)

        then:
        connection.registers().get(Ens160Minimal.REG_TEMP_IN) == (tempRaw & 0xFF)
        connection.registers().get(Ens160Minimal.REG_TEMP_IN + 1) == ((tempRaw >> 8) & 0xFF)
        connection.registers().get(Ens160Minimal.REG_RH_IN) == (rhRaw & 0xFF)
        connection.registers().get(Ens160Minimal.REG_RH_IN + 1) == ((rhRaw >> 8) & 0xFF)

        when: "DATA_T/DATA_RH actuals (4 bytes): tempRaw=0x5202 (~55.0 degC), rhRaw=0x6400 (50.0 %RH)"
        connection.setRegister(Ens160Minimal.REG_DATA_T, 0x02, 0x52, 0x00, 0x64)
        def actuals = sensor.readCompensationActuals()

        then:
        Math.abs(actuals[0] - ((0x5202 / 64.0d) - 273.15d)) < 1e-9
        actuals[1] == 0x6400 / 512.0d

        when: "GPR_READ sensor 1 (offset 0): raw=2048 -> resistance = 2**(2048/2048) = 2.0 Ohm"
        connection.setRegister(Ens160Minimal.REG_GPR_READ, 0x00, 0x08)

        then:
        sensor.readRawResistance(1) == 2.0d

        when: "GPR_READ sensor 4 (offset 6): raw=4096 -> resistance = 2**(4096/2048) = 4.0 Ohm"
        connection.setRegister(Ens160Minimal.REG_GPR_READ + 6, 0x00, 0x10)

        then:
        sensor.readRawResistance(4) == 4.0d

        when:
        sensor.readRawResistance(2)

        then:
        thrown(IllegalArgumentException)

        when: "GET_APPVER response at GPR_READ+4: major=1, minor=2, release=3"
        connection.setRegister(Ens160Minimal.REG_GPR_READ + 4, 1, 2, 3)

        then:
        sensor.getFirmwareVersion() == [1, 2, 3] as int[]

        when:
        sensor.configureInterrupt(true, true, true, true, true)
        def configWrites = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Ens160Minimal.REG_CONFIG }

        then:
        configWrites.last()[1] == (byte) 0x6B

        when:
        sensor.sleep()

        then:
        connection.writes().last()[1] == (byte) Ens160Minimal.OPMODE_DEEP_SLEEP

        when:
        sensor.wake()

        then:
        connection.writes().last()[1] == (byte) Ens160Minimal.OPMODE_STANDARD
    }
}
