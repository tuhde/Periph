package power

import (
	"errors"
	"math"
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// axp2101Mock is an in-memory fake connection.RegisterConnection for unit
// tests — no hardware, no bus.
type axp2101Mock struct {
	registers map[byte]byte
	writes    [][]byte
	dead      bool
}

func newAXP2101Mock(regs map[byte][]byte) *axp2101Mock {
	m := &axp2101Mock{registers: map[byte]byte{0x30: 0x03, 0x18: 0x0A}}
	for reg, vals := range regs {
		m.set(reg, vals...)
	}
	return m
}

func (m *axp2101Mock) set(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *axp2101Mock) Write(data []byte) error {
	m.writes = append(m.writes, append([]byte(nil), data...))
	for i := 1; i < len(data); i++ {
		m.registers[data[0]+byte(i-1)] = data[i]
	}
	return nil
}

func (m *axp2101Mock) Read(n int) ([]byte, error) { return make([]byte, n), nil }

func (m *axp2101Mock) WriteRead(data []byte, n int) ([]byte, error) {
	if m.dead {
		return nil, errors.New("nack")
	}
	out := make([]byte, n)
	for i := range out {
		out[i] = m.registers[data[0]+byte(i)]
	}
	return out, nil
}

func (m *axp2101Mock) ReadReg(reg uint32, length int) ([]byte, error) {
	return m.WriteRead([]byte{byte(reg)}, length)
}

func (m *axp2101Mock) WriteReg(reg uint32, data []byte) error {
	return m.Write(append([]byte{byte(reg)}, data...))
}

func (m *axp2101Mock) Close() error                { return nil }
func (m *axp2101Mock) Enable()                     {}
func (m *axp2101Mock) Disable()                    {}
func (m *axp2101Mock) IsEnabled() bool             { return true }
func (m *axp2101Mock) IntPin() connection.InputPin { return nil }
func (m *axp2101Mock) EnPin() connection.OutputPin { return nil }

func (m *axp2101Mock) last(reg byte) int {
	for i := len(m.writes) - 1; i >= 0; i-- {
		if w := m.writes[i]; len(w) == 2 && w[0] == reg {
			return int(w[1])
		}
	}
	return -1
}

func newAXP2101Full(t *testing.T, regs map[byte][]byte) (*axp2101Mock, *AXP2101Full) {
	t.Helper()
	m := newAXP2101Mock(regs)
	f, err := NewAXP2101Full(m)
	if err != nil {
		t.Fatalf("NewAXP2101Full: %v", err)
	}
	m.writes = nil
	return m, f
}

func TestAXP2101MinimalInitAndReads(t *testing.T) {
	m := newAXP2101Mock(map[byte][]byte{0x34: {0x0F, 0xA0}, 0xA4: {87}, 0x00: {0x20}, 0x01: {0x22}})
	c, err := NewAXP2101Minimal(m)
	if err != nil {
		t.Fatal(err)
	}
	if m.last(0x30) != 0x1F {
		t.Errorf("ADC_ENABLE = %#x, want 0x1F", m.last(0x30))
	}
	if m.last(0x18) != 0x0A {
		t.Errorf("MODULE_ENABLE = %#x, want 0x0A", m.last(0x18))
	}
	if v, _ := c.BatteryVoltage(); math.Abs(v-4.0) > 1e-9 {
		t.Errorf("BatteryVoltage = %v", v)
	}
	if p, _ := c.BatteryPercent(); p != 87 {
		t.Errorf("BatteryPercent = %d", p)
	}
	if s, _ := c.ChargeState(); s != AXP2101ChargeCC {
		t.Errorf("ChargeState = %d", s)
	}
	if v, _ := c.VbusPresent(); !v {
		t.Error("VbusPresent false")
	}
	m.set(0x00, 0x00)
	if v, _ := c.VbusPresent(); v {
		t.Error("VbusPresent true")
	}
}

func TestAXP2101InitPreservesGaugeBits(t *testing.T) {
	m := newAXP2101Mock(map[byte][]byte{0x18: {0x02}})
	if _, err := NewAXP2101Minimal(m); err != nil {
		t.Fatal(err)
	}
	if m.last(0x18) != 0x0A {
		t.Errorf("MODULE_ENABLE = %#x, want 0x0A", m.last(0x18))
	}
}

func TestAXP2101AdcMasksDebugBits(t *testing.T) {
	m := newAXP2101Mock(map[byte][]byte{0x34: {0xCF, 0xA0}})
	c, _ := NewAXP2101Minimal(m)
	if v, _ := c.BatteryVoltage(); math.Abs(v-4.0) > 1e-9 {
		t.Errorf("BatteryVoltage = %v, want 4.0", v)
	}
}

func TestAXP2101BusFailure(t *testing.T) {
	m := newAXP2101Mock(nil)
	m.dead = true
	if _, err := NewAXP2101Minimal(m); err == nil {
		t.Error("expected an error from a dead bus")
	}
}

func TestAXP2101FullMeasurements(t *testing.T) {
	_, f := newAXP2101Full(t, map[byte][]byte{
		0x34: {0x0F, 0xA0}, 0x36: {0x03, 0xE8}, 0x38: {0x13, 0x88}, 0x3A: {0x10, 0x68}, 0x3C: {0x1C, 0x6A}, 0xA4: {50},
	})
	if v, _ := f.VbusVoltage(); math.Abs(v-5.0) > 1e-9 {
		t.Errorf("VbusVoltage = %v", v)
	}
	if v, _ := f.SystemVoltage(); math.Abs(v-4.2) > 1e-9 {
		t.Errorf("SystemVoltage = %v", v)
	}
	if v, _ := f.TSVoltage(); math.Abs(v-0.5) > 1e-9 {
		t.Errorf("TSVoltage = %v", v)
	}
	if v, _ := f.DieTemperature(); math.Abs(v-22.0) > 1e-9 {
		t.Errorf("DieTemperature = %v", v)
	}
	r, err := f.ReadAll()
	if err != nil || r.BatteryPercent != 50 || math.Abs(r.BatteryVoltage-4.0) > 1e-9 {
		t.Errorf("ReadAll = %+v, %v", r, err)
	}
}

func TestAXP2101Status(t *testing.T) {
	_, f := newAXP2101Full(t, map[byte][]byte{0x00: {0x3F}, 0x01: {0x4B}})
	s, err := f.Status()
	if err != nil {
		t.Fatal(err)
	}
	if !(s.VbusGood && s.BatfetOpen && s.BatteryPresent && s.BatteryActive && s.ThermalRegulation && s.CurrentLimit) {
		t.Errorf("STATUS1 flags wrong: %+v", s)
	}
	if s.BatteryDirection != "discharge" || s.SystemOn || !s.Vindpm || s.ChargeState != 3 {
		t.Errorf("STATUS2 flags wrong: %+v", s)
	}
}

func TestAXP2101GetRail(t *testing.T) {
	_, f := newAXP2101Full(t, map[byte][]byte{0x80: {0x0F}, 0x83: {0x80 | 18}})
	en, v, err := f.GetRail("dcdc2")
	if err != nil || !en || math.Abs(v-0.68) > 1e-9 {
		t.Errorf("GetRail = %v %v %v", en, v, err)
	}
	if _, _, err := f.GetRail("nope"); err == nil {
		t.Error("expected unknown rail error")
	}
}

type axp2101Case struct {
	name   string
	regs   map[byte][]byte
	action func(f *AXP2101Full) error
	want   [][2]int // register, last write
}

func TestAXP2101Setters(t *testing.T) {
	r := func(reg byte, v ...byte) map[byte][]byte { return map[byte][]byte{reg: v} }
	cases := []axp2101Case{
		{"set_adc_channels", nil, func(f *AXP2101Full) error { return f.SetADCChannels(true, false, true, false, false, true) }, [][2]int{{0x30, 0x25}}},
		{"adc_low_frequency", r(0x36, 0), func(f *AXP2101Full) error { return f.SetADCLowFrequency(true) }, [][2]int{{0x36, 0x80}}},
		{"set_charging_off", r(0x18, 0x0A), func(f *AXP2101Full) error { return f.SetCharging(false) }, [][2]int{{0x18, 0x08}}},
		{"set_charging_on", r(0x18, 0x08), func(f *AXP2101Full) error { return f.SetCharging(true) }, [][2]int{{0x18, 0x0A}}},
		{"charge_voltage_4v2", nil, func(f *AXP2101Full) error { return f.SetChargeVoltage(4.2) }, [][2]int{{0x64, 3}}},
		{"charge_voltage_4v4", nil, func(f *AXP2101Full) error { return f.SetChargeVoltage(4.4) }, [][2]int{{0x64, 5}}},
		{"charge_current_0.5A", r(0x64, 3), func(f *AXP2101Full) error { return f.SetChargeCurrent(0.5) }, [][2]int{{0x62, 11}}},
		{"charge_current_0.1A", r(0x64, 3), func(f *AXP2101Full) error { return f.SetChargeCurrent(0.1) }, [][2]int{{0x62, 4}}},
		{"charge_current_1.4A", r(0x64, 3), func(f *AXP2101Full) error { return f.SetChargeCurrent(1.4) }, [][2]int{{0x62, 20}}},
		{"charge_current_1.5A_at_4v0", r(0x64, 1), func(f *AXP2101Full) error { return f.SetChargeCurrent(1.5) }, [][2]int{{0x62, 21}}},
		{"precharge_current", r(0x61, 0xF0), func(f *AXP2101Full) error { return f.SetPrechargeCurrent(0.125) }, [][2]int{{0x61, 0xF5}}},
		{"termination_enabled", r(0x63, 0xE0), func(f *AXP2101Full) error { return f.SetTermination(0.05, true) }, [][2]int{{0x63, 0xF2}}},
		{"termination_disabled", r(0x63, 0xE0), func(f *AXP2101Full) error { return f.SetTermination(0.05, false) }, [][2]int{{0x63, 0xE2}}},
		{"input_current_limit", nil, func(f *AXP2101Full) error { return f.SetInputCurrentLimit(2.0) }, [][2]int{{0x16, 5}}},
		{"input_voltage_limit", nil, func(f *AXP2101Full) error { return f.SetInputVoltageLimit(4.36) }, [][2]int{{0x15, 6}}},
		{"min_system_voltage", nil, func(f *AXP2101Full) error { return f.SetMinSystemVoltage(3.7) }, [][2]int{{0x14, 5}}},
		{"charge_timers_default", r(0x67, 0x08), func(f *AXP2101Full) error { return f.SetChargeTimers(60, 12, true, true, true) }, [][2]int{{0x67, 0xEE}}},
		{"charge_timers_custom", r(0x67, 0x08), func(f *AXP2101Full) error { return f.SetChargeTimers(70, 20, false, false, false) }, [][2]int{{0x67, 0x3B}}},
		{"thermal_regulation", nil, func(f *AXP2101Full) error { return f.SetThermalRegulation(120) }, [][2]int{{0x65, 3}}},
		{"die_protection", nil, func(f *AXP2101Full) error { return f.SetDieProtection(135, false) }, [][2]int{{0x13, 0x04}}},
		{"battery_detection_off", r(0x68, 1), func(f *AXP2101Full) error { return f.SetBatteryDetection(false) }, [][2]int{{0x68, 0}}},
		{"backup_charging", r(0x6A, 0xF8), func(f *AXP2101Full) error { return f.SetBackupCharging(true, 3.3) }, [][2]int{{0x6A, 0xFF}, {0x18, 0x0E}}},
		{"chgled_manual_blink_4hz", r(0x69, 0xC0), func(f *AXP2101Full) error { return f.SetChgled("manual", "blink_4hz") }, [][2]int{{0x69, 0xE5}}},
		{"chgled_type_b", r(0x69, 0xC0), func(f *AXP2101Full) error { return f.SetChgled("type_b", "hiz") }, [][2]int{{0x69, 0xC3}}},
		{"chgled_off", r(0x69, 0xC1), func(f *AXP2101Full) error { return f.SetChgled("off", "hiz") }, [][2]int{{0x69, 0xC0}}},
		{"configure_ts", r(0x50, 0x20), func(f *AXP2101Full) error { return f.ConfigureTS(false, 60, "always") }, [][2]int{{0x50, 0x3F}}},
		{"ts_limits", nil, func(f *AXP2101Full) error { return f.SetTSLimits(1.312, 0.176, 1.984, 0.152) }, [][2]int{{0x54, 41}, {0x55, 88}, {0x56, 62}, {0x57, 76}}},
		{"ts_hysteresis", nil, func(f *AXP2101Full) error { return f.SetTSHysteresis(0.032, 0.004) }, [][2]int{{0x52, 2}, {0x53, 1}}},
		{"jeita", r(0x59, 0x80), func(f *AXP2101Full) error { return f.ConfigureJEITA(true, 0.8, 0.24, true, false, 0, 1) }, [][2]int{{0x59, 0x94}, {0x5A, 50}, {0x5B, 30}, {0x58, 1}}},
		{"rail_dcdc2_high_range", r(0x83, 0x80), func(f *AXP2101Full) error { return f.SetRailVoltage("dcdc2", 1.5) }, [][2]int{{0x83, 0x80 | 85}}},
		{"rail_dcdc2_1v2", r(0x83, 0x80), func(f *AXP2101Full) error { return f.SetRailVoltage("dcdc2", 1.2) }, [][2]int{{0x83, 0x80 | 70}}},
		{"rail_dcdc1_3v3", nil, func(f *AXP2101Full) error { return f.SetRailVoltage("dcdc1", 3.3) }, [][2]int{{0x82, 18}}},
		{"rail_dcdc4_max", nil, func(f *AXP2101Full) error { return f.SetRailVoltage("dcdc4", 1.84) }, [][2]int{{0x85, 102}}},
		{"rail_aldo2_3v3", nil, func(f *AXP2101Full) error { return f.SetRailVoltage("aldo2", 3.3) }, [][2]int{{0x93, 28}}},
		{"rail_cpusldo_1v0", nil, func(f *AXP2101Full) error { return f.SetRailVoltage("cpusldo", 1.0) }, [][2]int{{0x98, 10}}},
		{"rail_dldo1_3v3", nil, func(f *AXP2101Full) error { return f.SetRailVoltage("dldo1", 3.3) }, [][2]int{{0x99, 28}}},
		{"rail_dldo2_1v4", nil, func(f *AXP2101Full) error { return f.SetRailVoltage("dldo2", 1.4) }, [][2]int{{0x9A, 18}}},
		{"rail_enable_bldo2", r(0x90, 0), func(f *AXP2101Full) error { return f.SetRailEnabled("bldo2", true) }, [][2]int{{0x90, 0x20}}},
		{"rail_enable_dldo1_keeps_bldo2", r(0x90, 0x20), func(f *AXP2101Full) error { return f.SetRailEnabled("dldo1", true) }, [][2]int{{0x90, 0xA0}}},
		{"rail_disable_bldo2", r(0x90, 0xA0), func(f *AXP2101Full) error { return f.SetRailEnabled("bldo2", false) }, [][2]int{{0x90, 0x80}}},
		{"rail_enable_dldo2", r(0x91, 0), func(f *AXP2101Full) error { return f.SetRailEnabled("dldo2", true) }, [][2]int{{0x91, 0x01}}},
		{"dcdc3_always_pwm_dvm", r(0x81, 0), func(f *AXP2101Full) error { return f.ConfigureDCDC("dcdc3", true, 1) }, [][2]int{{0x81, 0x10}, {0x84, 0x80}}},
		{"dcdc_global", map[byte][]byte{0x80: {0x0F}, 0x81: {0x10}}, func(f *AXP2101Full) error { return f.ConfigureDCDCGlobal(true, true, true, true, 240) }, [][2]int{{0x80, 0x6F}, {0x81, 0xD3}}},
		{"off_discharge_off_keeps_reserved", r(0x10, 0x30), func(f *AXP2101Full) error { return f.SetOffDischarge(false) }, [][2]int{{0x10, 0x10}}},
		{"power_off_sets_bit0", r(0x10, 0x30), func(f *AXP2101Full) error { return f.PowerOff() }, [][2]int{{0x10, 0x31}}},
		{"restart_sets_bit1", r(0x10, 0x30), func(f *AXP2101Full) error { return f.Restart() }, [][2]int{{0x10, 0x32}}},
		{"sleep_bit0", r(0x26, 0x08), func(f *AXP2101Full) error { return f.Sleep() }, [][2]int{{0x26, 0x09}}},
		{"wakeup_bit1", r(0x26, 0x08), func(f *AXP2101Full) error { return f.Wakeup() }, [][2]int{{0x26, 0x0A}}},
		{"configure_sleep", r(0x26, 0x03), func(f *AXP2101Full) error { return f.ConfigureSleep(true, false, true) }, [][2]int{{0x26, 0x17}}},
		{"power_key", map[byte][]byte{0x27: {0xC0}, 0x22: {0x04}, 0x10: {0x30}}, func(f *AXP2101Full) error { return f.ConfigurePowerKey(128, 10, 2.5, true, true, true) }, [][2]int{{0x27, 0xFC}, {0x22, 0x07}, {0x10, 0x34}}},
		{"power_off_config", map[byte][]byte{0x22: {0x03}, 0x23: {0}}, func(f *AXP2101Full) error { return f.ConfigurePowerOff(false, 3.3, true, 0x05) }, [][2]int{{0x22, 0x03}, {0x24, 7}, {0x23, 0x25}}},
		{"pwrok", map[byte][]byte{0x25: {0x20}, 0x10: {0x30}}, func(f *AXP2101Full) error { return f.ConfigurePwrok(true, 64, true, true, true) }, [][2]int{{0x25, 0x3F}, {0x10, 0x38}}},
		{"batfet_off_enable", r(0x12, 0), func(f *AXP2101Full) error { return f.SetBatfetOffEnable(true) }, [][2]int{{0x12, 0x08}}},
		{"fast_poweron", map[byte][]byte{0x28: {0xFF}, 0x2B: {0x0F}}, func(f *AXP2101Full) error {
			return f.ConfigureFastPoweron(true, false, map[string]int{"dcdc1": 1, "dcdc4": -1, "dldo2": 2})
		}, [][2]int{{0x28, 0xFD}, {0x2B, 0x8B}}},
		{"watchdog", map[byte][]byte{0x19: {0}, 0x18: {0x0A}}, func(f *AXP2101Full) error { return f.ConfigureWatchdog(true, 128, "power_cycle") }, [][2]int{{0x19, 0x37}, {0x18, 0x0B}}},
		{"feed_watchdog", r(0x19, 0x06), func(f *AXP2101Full) error { return f.FeedWatchdog() }, [][2]int{{0x19, 0x0E}}},
		{"battery_warning", nil, func(f *AXP2101Full) error { return f.ConfigureBatteryWarning(15, 5) }, [][2]int{{0x1A, 0xA5}}},
		{"gauge_enable", r(0x18, 0x02), func(f *AXP2101Full) error { return f.SetGaugeEnabled(true) }, [][2]int{{0x18, 0x0A}}},
		{"reset_gauge", r(0x17, 0), func(f *AXP2101Full) error { return f.ResetGauge(false) }, [][2]int{{0x17, 0x08}}},
		{"reset_gauge_full", r(0x17, 0), func(f *AXP2101Full) error { return f.ResetGauge(true) }, [][2]int{{0x17, 0x04}}},
		{"gpio1_low", r(0x1B, 0), func(f *AXP2101Full) error { return f.SetGPIO1(true) }, [][2]int{{0x1B, 0x04}}},
		{"gpio1_hiz", r(0x1B, 0x04), func(f *AXP2101Full) error { return f.SetGPIO1(false) }, [][2]int{{0x1B, 0x00}}},
		{"data_buffer_write", nil, func(f *AXP2101Full) error { return f.WriteDataBuffer(2, 0xAB) }, [][2]int{{0x06, 0xAB}}},
		{"irq_disable", r(0x40, 0xFF, 0xFC, 0x5F), func(f *AXP2101Full) error {
			return f.DisableInterrupt(AXP2101SourceVbusInsert | AXP2101SourceChargeDone)
		}, [][2]int{{0x41, 0x7C}, {0x42, 0x4F}}},
		{"irq_enable", r(0x40, 0x00, 0x7C, 0x4F), func(f *AXP2101Full) error {
			return f.EnableInterrupt(AXP2101SourceVbusInsert | AXP2101SourcePwronShort)
		}, [][2]int{{0x41, 0xFC}}},
		{"irq_enable_en0_only", r(0x40, 0, 0, 0), func(f *AXP2101Full) error { return f.EnableInterrupt(AXP2101SourceSocWarnL2) }, [][2]int{{0x40, 0x80}}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			m, f := newAXP2101Full(t, tc.regs)
			if err := tc.action(f); err != nil {
				t.Fatal(err)
			}
			for _, w := range tc.want {
				if got := m.last(byte(w[0])); got != w[1] {
					t.Errorf("register %#02x = %#x, want %#x", w[0], got, w[1])
				}
			}
		})
	}
}

func TestAXP2101Rejects(t *testing.T) {
	cases := []struct {
		name   string
		action func(f *AXP2101Full) error
	}{
		{"charge_voltage_invalid", func(f *AXP2101Full) error { return f.SetChargeVoltage(4.25) }},
		{"charge_current_over_cv_limit", func(f *AXP2101Full) error { return f.SetChargeCurrent(1.5) }},
		{"charge_current_negative", func(f *AXP2101Full) error { return f.SetChargeCurrent(-0.1) }},
		{"precharge_out_of_range", func(f *AXP2101Full) error { return f.SetPrechargeCurrent(0.3) }},
		{"input_current_limit_invalid", func(f *AXP2101Full) error { return f.SetInputCurrentLimit(1.2) }},
		{"jeita_bad_drop", func(f *AXP2101Full) error { return f.ConfigureJEITA(true, -1, -1, true, false, 3, 0) }},
		{"rail_dcdc3_over_max", func(f *AXP2101Full) error { return f.SetRailVoltage("dcdc3", 1.84) }},
		{"rail_dldo1_over", func(f *AXP2101Full) error { return f.SetRailVoltage("dldo1", 3.5) }},
		{"rail_unknown", func(f *AXP2101Full) error { return f.SetRailVoltage("foo", 1.0) }},
		{"rail_below_range", func(f *AXP2101Full) error { return f.SetRailVoltage("aldo1", 0.3) }},
		{"dcdc1_dvm_rejected", func(f *AXP2101Full) error { return f.ConfigureDCDC("dcdc1", false, 1) }},
		{"power_key_bad_value", func(f *AXP2101Full) error { return f.ConfigurePowerKey(100, 6, 1.5, true, false, false) }},
		{"fast_poweron_unknown_rail", func(f *AXP2101Full) error { return f.ConfigureFastPoweron(true, true, map[string]int{"x": 1}) }},
		{"watchdog_bad_timeout", func(f *AXP2101Full) error { return f.ConfigureWatchdog(true, 3, "irq") }},
		{"battery_warning_range", func(f *AXP2101Full) error { return f.ConfigureBatteryWarning(25, 1) }},
		{"data_buffer_index", func(f *AXP2101Full) error { _, err := f.ReadDataBuffer(4); return err }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, f := newAXP2101Full(t, map[byte][]byte{0x64: {3}})
			if err := tc.action(f); err == nil {
				t.Error("expected an error")
			}
		})
	}
}

func TestAXP2101DataBufferAndSources(t *testing.T) {
	_, f := newAXP2101Full(t, map[byte][]byte{0x20: {0x21}, 0x21: {0x81}})
	if err := f.WriteDataBuffer(2, 0xAB); err != nil {
		t.Fatal(err)
	}
	if v, _ := f.ReadDataBuffer(2); v != 0xAB {
		t.Errorf("data buffer = %#x", v)
	}
	if v, _ := f.PowerOnSource(); v != 0x21 {
		t.Errorf("PowerOnSource = %#x", v)
	}
	if v, _ := f.PowerOffSource(); v != 0x81 {
		t.Errorf("PowerOffSource = %#x", v)
	}
}

func TestAXP2101PollInterrupt(t *testing.T) {
	m, f := newAXP2101Full(t, map[byte][]byte{0x48: {0x10, 0x88, 0x01}})
	st, err := f.PollInterrupt()
	if err != nil || st != 0x018810 {
		t.Fatalf("PollInterrupt = %#x, %v", st, err)
	}
	if m.last(0x48) != 0x10 || m.last(0x49) != 0x88 || m.last(0x4A) != 0x01 {
		t.Errorf("status bits not written back: %#x %#x %#x", m.last(0x48), m.last(0x49), m.last(0x4A))
	}
	m.set(0x48, 0, 0, 0)
	m.writes = nil
	if st, _ := f.PollInterrupt(); st != 0 || len(m.writes) != 0 {
		t.Errorf("idle poll = %#x, writes %d", st, len(m.writes))
	}
}
