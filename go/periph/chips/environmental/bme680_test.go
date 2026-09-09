package environmental

import (
	"math"
	"testing"
)

// Calibration block 1 (23 bytes from 0x8A). No published worked example
// exists for BME680 (see spec's Data Conversion > Validation) - these are
// self-consistent, hand-derived values used to check every language's
// translation against the same formula.
var bme680Cal1 = []byte{
	0x43, 0x67, 0x03, 0x00, 0x7D, 0x8E, 0x43, 0xD6, 0x58, 0x00, 0x27,
	0x0B, 0x8C, 0x00, 0x0F, 0xF9, 0x00, 0x00, 0xF8, 0xC6, 0x70, 0x17, 0x1E,
}

// Calibration block 2 (14 bytes from 0xE1).
var bme680Cal2 = []byte{0x2B, 0xC8, 0x25, 0x00, 0x2D, 0x14, 0x78, 0x9C, 0x90, 0x65, 0x0C, 0xE5, 0xE2, 0x1E}

// Single-byte calibration: resHeatVal=50, resHeatRange=2, rangeSwitchingError=0.
const (
	bme680TestResHeatValByte   = 0x32
	bme680TestResHeatRangeByte = 0x20
	bme680TestRangeSwErrByte   = 0x00
)

// ADC burst (13 bytes from 0x1F): pressAdc=415148, tempAdc=419888,
// humAdc=20000, gasAdc=400, gasRange=5, gasValid=1, heatStab=1.
var bme680Adc = []byte{0x65, 0x5A, 0xC0, 0x66, 0x83, 0x00, 0x4E, 0x20, 0x00, 0x00, 0x00, 0x64, 0x35}

const (
	bme680ExpectedT   = 1.23
	bme680ExpectedP   = 969.4
	bme680ExpectedH   = 39.826
	bme680ExpectedGas = 271155.0
)

func TestBME680FullAPI(t *testing.T) {
	conn := newMockRegConnection()
	conn.setRegister(bme680RegCalBlock1, bme680Cal1...)
	conn.setRegister(bme680RegCalBlock2, bme680Cal2...)
	conn.setRegister(bme680RegResHeatVal, bme680TestResHeatValByte)
	conn.setRegister(bme680RegResHeatRange, bme680TestResHeatRangeByte)
	conn.setRegister(bme680RegRangeSwErr, bme680TestRangeSwErrByte)
	conn.setRegister(bme680RegPressMsb, bme680Adc...)

	sensor, err := NewBME680Full(conn)
	if err != nil {
		t.Fatalf("NewBME680Full: %v", err)
	}

	if v := conn.registers[bme680RegCtrlHum]; v != 1 {
		t.Errorf("init: ctrl_hum = 0x%02X, want 0x01", v)
	}
	if v := conn.registers[bme680RegCtrlMeas]; v != ((1 << 5) | (1 << 2) | 0) {
		t.Errorf("init: ctrl_meas = 0x%02X, want 0x24", v)
	}
	if v := conn.registers[bme680RegConfig]; v != 0 {
		t.Errorf("init: config = 0x%02X, want 0x00", v)
	}
	// Default heater profile 0: 320 degC target, 150 ms duration, ambient=25.0.
	if v := conn.registers[bme680RegResHeat0]; v != 0x52 {
		t.Errorf("init: res_heat_0 = 0x%02X, want 0x52", v)
	}
	if v := conn.registers[bme680RegGasWait0]; v != 0x65 {
		t.Errorf("init: gas_wait_0 = 0x%02X, want 0x65", v)
	}
	if v := conn.registers[bme680RegCtrlGas1]; v != ((1 << 4) | 0) {
		t.Errorf("init: ctrl_gas_1 = 0x%02X, want 0x10", v)
	}

	if err := sensor.SetHeater(300, 200); err != nil {
		t.Fatalf("SetHeater: %v", err)
	}
	if v := conn.registers[bme680RegResHeat0]; v != 0x4E {
		t.Errorf("SetHeater: res_heat_0 = 0x%02X, want 0x4E", v)
	}
	if v := conn.registers[bme680RegGasWait0]; v != 0x72 {
		t.Errorf("SetHeater: gas_wait_0 = 0x%02X, want 0x72", v)
	}

	if err := sensor.SetHeaterProfile(4, 280, 50); err != nil {
		t.Fatalf("SetHeaterProfile: %v", err)
	}
	if v := conn.registers[bme680RegResHeat0+4]; v != 0x49 {
		t.Errorf("SetHeaterProfile: res_heat_4 = 0x%02X, want 0x49", v)
	}
	if v := conn.registers[bme680RegGasWait0+4]; v != 0x32 {
		t.Errorf("SetHeaterProfile: gas_wait_4 = 0x%02X, want 0x32", v)
	}

	temp, err := sensor.Temperature()
	if err != nil || math.Abs(float64(temp)-bme680ExpectedT) >= 0.01 {
		t.Errorf("Temperature() = %v, %v, want ~%v", temp, err, bme680ExpectedT)
	}
	press, err := sensor.Pressure()
	if err != nil || math.Abs(float64(press)-bme680ExpectedP) >= 0.1 {
		t.Errorf("Pressure() = %v, %v, want ~%v", press, err, bme680ExpectedP)
	}
	hum, err := sensor.Humidity()
	if err != nil || math.Abs(float64(hum)-bme680ExpectedH) >= 0.01 {
		t.Errorf("Humidity() = %v, %v, want ~%v", hum, err, bme680ExpectedH)
	}
	gas, err := sensor.GasResistance()
	if err != nil || math.Abs(float64(gas)-bme680ExpectedGas) >= 1.0 {
		t.Errorf("GasResistance() = %v, %v, want ~%v", gas, err, bme680ExpectedGas)
	}

	if last := lastRegWrite(conn.writes, bme680RegCtrlMeas); last == nil || last[1]&0x03 != 1 {
		t.Errorf("trigger: expected last ctrl_meas write with mode=forced, got %v", last)
	}

	if err := sensor.Configure(2, 3, 1, 0, 3); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if v := conn.registers[bme680RegCtrlHum]; v != 1 {
		t.Errorf("Configure: ctrl_hum = 0x%02X, want 0x01", v)
	}
	if v := conn.registers[bme680RegConfig]; v != (3 << 2) {
		t.Errorf("Configure: config = 0x%02X, want 0x0C", v)
	}
	if v := conn.registers[bme680RegCtrlMeas]; v != ((2 << 5) | (3 << 2) | 0) {
		t.Errorf("Configure: ctrl_meas = 0x%02X, want 0x4C", v)
	}

	if err := sensor.SetOversampling(3, 4, 2); err != nil {
		t.Fatalf("SetOversampling: %v", err)
	}
	if v := conn.registers[bme680RegCtrlHum]; v != 2 {
		t.Errorf("SetOversampling: ctrl_hum = 0x%02X, want 0x02", v)
	}
	if v := conn.registers[bme680RegCtrlMeas]; v != ((3 << 5) | (4 << 2) | 0) {
		t.Errorf("SetOversampling: ctrl_meas = 0x%02X, want 0x70", v)
	}

	if err := sensor.SetFilter(5); err != nil {
		t.Fatalf("SetFilter: %v", err)
	}
	if v := conn.registers[bme680RegConfig]; v != (5 << 2) {
		t.Errorf("SetFilter: config = 0x%02X, want 0x14", v)
	}

	if err := sensor.SelectHeaterProfile(2); err != nil {
		t.Fatalf("SelectHeaterProfile: %v", err)
	}
	if v := conn.registers[bme680RegCtrlGas1]; v != ((1 << 4) | 2) {
		t.Errorf("SelectHeaterProfile: ctrl_gas_1 = 0x%02X, want 0x12", v)
	}

	if err := sensor.SetGasEnabled(false); err != nil {
		t.Fatalf("SetGasEnabled: %v", err)
	}
	if v := conn.registers[bme680RegCtrlGas1]; v != 2 {
		t.Errorf("SetGasEnabled(false): ctrl_gas_1 = 0x%02X, want 0x02", v)
	}
	if err := sensor.SetGasEnabled(true); err != nil {
		t.Fatalf("SetGasEnabled: %v", err)
	}
	if v := conn.registers[bme680RegCtrlGas1]; v != ((1 << 4) | 2) {
		t.Errorf("SetGasEnabled(true): ctrl_gas_1 = 0x%02X, want 0x12", v)
	}

	if err := sensor.SetHeaterOff(true); err != nil {
		t.Fatalf("SetHeaterOff: %v", err)
	}
	if v := conn.registers[bme680RegCtrlGas0]; v != 0x08 {
		t.Errorf("SetHeaterOff(true): ctrl_gas_0 = 0x%02X, want 0x08", v)
	}
	if err := sensor.SetHeaterOff(false); err != nil {
		t.Fatalf("SetHeaterOff: %v", err)
	}
	if v := conn.registers[bme680RegCtrlGas0]; v != 0x00 {
		t.Errorf("SetHeaterOff(false): ctrl_gas_0 = 0x%02X, want 0x00", v)
	}

	rt, rp, rh, rg, err := sensor.ReadAll()
	if err != nil {
		t.Fatalf("ReadAll: %v", err)
	}
	if math.Abs(float64(rt)-bme680ExpectedT) >= 0.01 {
		t.Errorf("ReadAll() t = %v, want ~%v", rt, bme680ExpectedT)
	}
	if math.Abs(float64(rp)-bme680ExpectedP) >= 0.1 {
		t.Errorf("ReadAll() p = %v, want ~%v", rp, bme680ExpectedP)
	}
	if math.Abs(float64(rh)-bme680ExpectedH) >= 0.01 {
		t.Errorf("ReadAll() h = %v, want ~%v", rh, bme680ExpectedH)
	}
	if math.Abs(float64(rg)-bme680ExpectedGas) >= 1.0 {
		t.Errorf("ReadAll() g = %v, want ~%v", rg, bme680ExpectedGas)
	}

	if ok, _ := sensor.GasValid(); !ok {
		t.Errorf("GasValid() = false, want true")
	}
	if ok, _ := sensor.HeaterStable(); !ok {
		t.Errorf("HeaterStable() = false, want true")
	}

	conn.setRegister(bme680RegMeasStatus, 0xA0)
	if v, _ := sensor.Status(); v != 0xA0 {
		t.Errorf("Status() = 0x%02X, want 0xA0", v)
	}

	conn.setRegister(bme680RegID, bme680ChipID)
	if v, _ := sensor.ChipID(); v != 0x61 {
		t.Errorf("ChipID() = 0x%02X, want 0x61", v)
	}

	if err := sensor.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	sawReset := false
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == bme680RegReset && w[1] == bme680ResetCmd {
			sawReset = true
		}
	}
	if !sawReset {
		t.Errorf("Reset: expected reset command write")
	}
	if v := conn.registers[bme680RegCtrlHum]; v != 2 {
		t.Errorf("Reset: ctrl_hum = 0x%02X, want 0x02 (reapplied)", v)
	}
	if v := conn.registers[bme680RegConfig]; v != (5 << 2) {
		t.Errorf("Reset: config = 0x%02X, want 0x14 (reapplied)", v)
	}
	if v := conn.registers[bme680RegCtrlMeas]; v != ((3 << 5) | (4 << 2) | 0) {
		t.Errorf("Reset: ctrl_meas = 0x%02X, want 0x70 (reapplied)", v)
	}
	if v := conn.registers[bme680RegCtrlGas1]; v != ((1 << 4) | 2) {
		t.Errorf("Reset: ctrl_gas_1 = 0x%02X, want 0x12 (reapplied)", v)
	}
}
