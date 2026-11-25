package it.uhde.periph.chips.power;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * AXP2101 — single-cell Li-ion NVDC power-management IC (X-Powers) — full interface.
 *
 * <p>Extends {@link AXP2101Minimal} with the remaining ADC channels, charger, battery-temperature,
 * power rail, power-key, sleep, watchdog, fuel-gauge and interrupt control. Setters validate their
 * argument and throw {@link IllegalArgumentException} for a value the chip cannot represent.
 *
 * <p>Dangerous operations: disabling or re-voltaging the rail that powers the host (commonly DCDC1
 * or DCDC3), {@link #powerOff()}, the power-off thresholds, charge voltage / current beyond the
 * cell's limits and {@link #setBatfetOffEnable(boolean)} can power the board down or damage the
 * battery.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (falling edge),
 * otherwise a 5&nbsp;ms polling thread.
 */
public class AXP2101Full extends AXP2101Minimal {

    /** Battery under-temperature while discharging. */
    public static final int SOURCE_BAT_UNDER_TEMP_WORK = 0x000001;
    /** Battery over-temperature while discharging. */
    public static final int SOURCE_BAT_OVER_TEMP_WORK = 0x000002;
    /** Battery under-temperature while charging. */
    public static final int SOURCE_BAT_UNDER_TEMP_CHG = 0x000004;
    /** Battery over-temperature while charging. */
    public static final int SOURCE_BAT_OVER_TEMP_CHG = 0x000008;
    /** Gauge new state of charge. */
    public static final int SOURCE_GAUGE_NEW_SOC = 0x000010;
    /** Gauge watchdog timeout. */
    public static final int SOURCE_GAUGE_WDT = 0x000020;
    /** State of charge at warning level 1 / shutdown level. */
    public static final int SOURCE_SOC_WARN_L1 = 0x000040;
    /** State of charge at warning level 2. */
    public static final int SOURCE_SOC_WARN_L2 = 0x000080;
    /** Power key released. */
    public static final int SOURCE_PWRON_POS_EDGE = 0x000100;
    /** Power key pressed. */
    public static final int SOURCE_PWRON_NEG_EDGE = 0x000200;
    /** Power key long press. */
    public static final int SOURCE_PWRON_LONG = 0x000400;
    /** Power key short press. */
    public static final int SOURCE_PWRON_SHORT = 0x000800;
    /** Battery removed. */
    public static final int SOURCE_BAT_REMOVE = 0x001000;
    /** Battery inserted. */
    public static final int SOURCE_BAT_INSERT = 0x002000;
    /** VBUS removed. */
    public static final int SOURCE_VBUS_REMOVE = 0x004000;
    /** VBUS inserted. */
    public static final int SOURCE_VBUS_INSERT = 0x008000;
    /** Battery over-voltage protection. */
    public static final int SOURCE_BAT_OVER_VOLTAGE = 0x010000;
    /** Charger safety timer expired. */
    public static final int SOURCE_CHARGER_TIMER = 0x020000;
    /** Die over-temperature level 1. */
    public static final int SOURCE_DIE_OVER_TEMP_L1 = 0x040000;
    /** Charging started. */
    public static final int SOURCE_CHARGE_START = 0x080000;
    /** Charging done. */
    public static final int SOURCE_CHARGE_DONE = 0x100000;
    /** LDO over-current. */
    public static final int SOURCE_LDO_OVER_CURRENT = 0x400000;
    /** Watchdog expired. */
    public static final int SOURCE_WATCHDOG_EXPIRE = 0x800000;

    /**
     * Every measurement returned by {@link #readAll()}.
     *
     * @param batteryVoltage battery voltage in V
     * @param vbusVoltage    VBUS voltage in V
     * @param systemVoltage  system voltage in V
     * @param tsVoltage      TS pin voltage in V
     * @param dieTemperature die temperature in °C
     * @param batteryPercent fuel-gauge state of charge, 0–100 %
     * @param chargeState    one of the {@code CHARGE_*} constants
     * @param vbusPresent    {@code true} when a good input source is connected
     */
    public record Readings(double batteryVoltage, double vbusVoltage, double systemVoltage,
                           double tsVoltage, double dieTemperature, int batteryPercent,
                           int chargeState, boolean vbusPresent) {}

    /**
     * Decoded STATUS1 / STATUS2 returned by {@link #status()}.
     *
     * @param vbusGood          input source good
     * @param batfetOpen        BATFET open
     * @param batteryPresent    battery present
     * @param batteryActive     battery in active mode
     * @param thermalRegulation charger in thermal regulation
     * @param currentLimit      in current limit
     * @param batteryDirection  {@code "standby"}, {@code "charge"}, {@code "discharge"} or {@code "reserved"}
     * @param systemOn          system powered on
     * @param vindpm            input voltage regulation active
     * @param chargeState       one of the {@code CHARGE_*} constants
     */
    public record Status(boolean vbusGood, boolean batfetOpen, boolean batteryPresent,
                         boolean batteryActive, boolean thermalRegulation, boolean currentLimit,
                         String batteryDirection, boolean systemOn, boolean vindpm,
                         int chargeState) {}

    /**
     * State of one power rail returned by {@link #getRail(String)}.
     *
     * @param enabled whether the rail is enabled
     * @param volts   programmed voltage in V
     */
    public record Rail(boolean enabled, double volts) {}

    // name -> {enable register, enable bit, voltage register, kind, voltage mask}
    // kind: 0 dcdc1, 1 dcdc2/3, 2 dcdc4, 3 ldo 100 mV, 4 ldo 50 mV, 5 dldo1
    private static final Map<String, int[]> RAILS = Map.ofEntries(
        Map.entry("dcdc1",   new int[] {0x80, 0, 0x82, 0, 0x1F}),
        Map.entry("dcdc2",   new int[] {0x80, 1, 0x83, 1, 0x7F}),
        Map.entry("dcdc3",   new int[] {0x80, 2, 0x84, 1, 0x7F}),
        Map.entry("dcdc4",   new int[] {0x80, 3, 0x85, 2, 0x7F}),
        Map.entry("aldo1",   new int[] {0x90, 0, 0x92, 3, 0x1F}),
        Map.entry("aldo2",   new int[] {0x90, 1, 0x93, 3, 0x1F}),
        Map.entry("aldo3",   new int[] {0x90, 2, 0x94, 3, 0x1F}),
        Map.entry("aldo4",   new int[] {0x90, 3, 0x95, 3, 0x1F}),
        Map.entry("bldo1",   new int[] {0x90, 4, 0x96, 3, 0x1F}),
        Map.entry("bldo2",   new int[] {0x90, 5, 0x97, 3, 0x1F}),
        Map.entry("cpusldo", new int[] {0x90, 6, 0x98, 4, 0x1F}),
        Map.entry("dldo1",   new int[] {0x90, 7, 0x99, 5, 0x1F}),
        Map.entry("dldo2",   new int[] {0x91, 0, 0x9A, 4, 0x1F}));

    // rail -> {register offset from 0x28, shift}
    private static final Map<String, int[]> FAST_RAILS = Map.ofEntries(
        Map.entry("dcdc1", new int[] {0, 0}), Map.entry("dcdc2", new int[] {0, 2}),
        Map.entry("dcdc3", new int[] {0, 4}), Map.entry("dcdc4", new int[] {0, 6}),
        Map.entry("aldo1", new int[] {1, 2}), Map.entry("aldo2", new int[] {1, 4}),
        Map.entry("aldo3", new int[] {1, 6}), Map.entry("aldo4", new int[] {2, 0}),
        Map.entry("bldo1", new int[] {2, 2}), Map.entry("bldo2", new int[] {2, 4}),
        Map.entry("cpusldo", new int[] {2, 6}),
        Map.entry("dldo1", new int[] {3, 0}), Map.entry("dldo2", new int[] {3, 2}));

    private IntConsumer callback;
    private InputPin subscribedPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver; the initialisation sequence is the same as {@link AXP2101Minimal}.
     *
     * @param connection register connection bound to I²C address 0x34
     * @throws IOException if the bus check fails
     */
    public AXP2101Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    // --- Helpers ----------------------------------------------------------

    private static int round(double x) {
        return (int) Math.floor(x + 0.5);
    }

    private static int stepCode(double value, double start, double step, int maxCode, String what) {
        int code = round((value - start) / step);
        if (code < 0 || code > maxCode || Math.abs(value - (start + code * step)) > step / 2 + 1e-9) {
            throw new IllegalArgumentException(what + " out of range: " + value);
        }
        return code;
    }

    private static int indexOf(double value, double[] keys, String what) {
        for (int i = 0; i < keys.length; i++) {
            if (Math.abs(keys[i] - value) < 1e-6) return i;
        }
        throw new IllegalArgumentException(what + " unsupported value: " + value);
    }

    private static int bit(boolean on, int b) {
        return on ? b : 0;
    }

    private static int[] railInfo(String name) {
        int[] info = RAILS.get(name);
        if (info == null) throw new IllegalArgumentException("unknown rail: " + name);
        return info;
    }

    private static int railCode(int kind, double volts, String name) {
        String what = name + " voltage";
        switch (kind) {
            case 0: return stepCode(volts, 1.5, 0.1, 19, what);
            case 3: return stepCode(volts, 0.5, 0.1, 30, what);
            case 4: return stepCode(volts, 0.5, 0.05, 19, what);
            case 5: return stepCode(volts, 0.5, 0.1, 28, what);
            default:
                int top = kind == 1 ? 87 : 102;
                if (volts <= 1.21) return stepCode(volts, 0.5, 0.01, 70, what);
                return 71 + stepCode(volts, 1.22, 0.02, top - 71, what);
        }
    }

    private static double railVolts(int kind, int code) {
        switch (kind) {
            case 0: return 1.5 + code * 0.1;
            case 3: case 5: return 0.5 + code * 0.1;
            case 4: return 0.5 + code * 0.05;
            default: return code <= 70 ? 0.5 + code * 0.01 : 1.22 + (code - 71) * 0.02;
        }
    }

    // --- Measurements -----------------------------------------------------

    /**
     * Read the VBUS voltage.
     *
     * @return VBUS voltage in V
     * @throws IOException on bus error
     */
    public double vbusVoltage() throws IOException {
        return adc14(REG_VBUS_H) * 0.001;
    }

    /**
     * Read the system (VSYS) voltage.
     *
     * @return VSYS voltage in V
     * @throws IOException on bus error
     */
    public double systemVoltage() throws IOException {
        return adc14(REG_VSYS_H) * 0.001;
    }

    /**
     * Read the TS pin voltage.
     *
     * @return TS voltage in V (0.5 mV / LSB)
     * @throws IOException on bus error
     */
    public double tsVoltage() throws IOException {
        return adc14(REG_TS_H) * 0.0005;
    }

    /**
     * Read the die temperature using the vendor XPowersLib formula (not from the datasheet;
     * verify on hardware).
     *
     * @return die temperature in °C
     * @throws IOException on bus error
     */
    public double dieTemperature() throws IOException {
        return 22 + (7274 - adc14(REG_TDIE_H)) / 20.0;
    }

    /**
     * Read every measurement.
     *
     * @return all measurements
     * @throws IOException on bus error
     */
    public Readings readAll() throws IOException {
        return new Readings(batteryVoltage(), vbusVoltage(), systemVoltage(), tsVoltage(),
                dieTemperature(), batteryPercent(), chargeState(), vbusPresent());
    }

    /**
     * Decode STATUS1 and STATUS2.
     *
     * @return the decoded flags
     * @throws IOException on bus error
     */
    public Status status() throws IOException {
        int s1 = readReg(REG_STATUS1);
        int s2 = readReg(REG_STATUS2);
        String[] directions = {"standby", "charge", "discharge", "reserved"};
        return new Status((s1 & 0x20) != 0, (s1 & 0x10) != 0, (s1 & 0x08) != 0, (s1 & 0x04) != 0,
                (s1 & 0x02) != 0, (s1 & 0x01) != 0, directions[(s2 >> 5) & 3],
                (s2 & 0x10) != 0, (s2 & 0x08) != 0, s2 & 0x07);
    }

    // --- ADC --------------------------------------------------------------

    /**
     * Select the enabled ADC channels (ADC_ENABLE).
     *
     * @param vbat  battery voltage channel
     * @param ts    TS pin channel
     * @param vbus  VBUS voltage channel
     * @param vsys  system voltage channel
     * @param tdie  die temperature channel
     * @param gpadc general-purpose ADC channel
     * @throws IOException on bus error
     */
    public void setAdcChannels(boolean vbat, boolean ts, boolean vbus, boolean vsys, boolean tdie,
                               boolean gpadc) throws IOException {
        rmw(REG_ADC_ENABLE, 0x3F, bit(vbat, 1) | bit(ts, 2) | bit(vbus, 4) | bit(vsys, 8)
                | bit(tdie, 16) | bit(gpadc, 32));
    }

    /**
     * Select slow (64 s) ADC sampling when powered off on battery only (TS_H bit 7).
     *
     * @param enabled {@code true} for low-frequency sampling
     * @throws IOException on bus error
     */
    public void setAdcLowFrequency(boolean enabled) throws IOException {
        rmw(REG_TS_H, 0x80, bit(enabled, 0x80));
    }

    // --- Charger ----------------------------------------------------------

    /**
     * Enable or disable main-cell charging (MODULE_ENABLE bit 1).
     *
     * @param enabled {@code true} to charge
     * @throws IOException on bus error
     */
    public void setCharging(boolean enabled) throws IOException {
        rmw(REG_MODULE_ENABLE, 0x02, bit(enabled, 0x02));
    }

    /**
     * Set the charge target voltage.
     *
     * @param volts 4.0, 4.1, 4.2, 4.35 or 4.4 V (the last two only for high-voltage cells)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException for any other value
     */
    public void setChargeVoltage(double volts) throws IOException {
        int i = indexOf(volts, new double[] {4.0, 4.1, 4.2, 4.35, 4.4}, "charge voltage");
        rmw(REG_CV, 0x07, i + 1);
    }

    /**
     * Set the constant-current charge current: 0–0.2 A in 25 mA steps, 0.2–1.5 A in 100 mA
     * steps, capped by the limit of the currently programmed charge voltage.
     *
     * @param amps charge current in A
     * @throws IOException              on bus error
     * @throws IllegalArgumentException out of range or above the charge-voltage limit
     */
    public void setChargeCurrent(double amps) throws IOException {
        if (amps < 0 || amps > 1.5 + 1e-9) {
            throw new IllegalArgumentException("charge current out of range: " + amps);
        }
        int cv = readReg(REG_CV) & 0x07;
        double limit = cv == 3 ? 1.4 : cv == 4 ? 1.1 : cv == 5 ? 1.0 : 1.5;
        if (amps > limit + 1e-9) {
            throw new IllegalArgumentException("charge current " + amps + " A exceeds " + limit
                    + " A limit for this charge voltage");
        }
        int code = amps <= 0.25 ? Math.min(8, round(amps / 0.025)) : 8 + round((amps - 0.2) / 0.1);
        rmw(REG_ICC, 0x1F, code);
    }

    /**
     * Set the pre-charge current.
     *
     * @param amps 0–0.2 A in 25 mA steps
     * @throws IOException on bus error
     */
    public void setPrechargeCurrent(double amps) throws IOException {
        rmw(REG_IPRECHG, 0x0F, stepCode(amps, 0.0, 0.025, 8, "precharge current"));
    }

    /**
     * Set the charge termination current.
     *
     * @param amps    0–0.2 A in 25 mA steps
     * @param enabled termination enable
     * @throws IOException on bus error
     */
    public void setTermination(double amps, boolean enabled) throws IOException {
        rmw(REG_ITERM, 0x1F, stepCode(amps, 0.0, 0.025, 8, "termination current") | bit(enabled, 0x10));
    }

    /**
     * Set the input current limit.
     *
     * @param amps 0.1, 0.5, 0.9, 1.0, 1.5 or 2.0 A
     * @throws IOException on bus error
     */
    public void setInputCurrentLimit(double amps) throws IOException {
        rmw(REG_IINLIM, 0x07, indexOf(amps, new double[] {0.1, 0.5, 0.9, 1.0, 1.5, 2.0},
                "input current limit"));
    }

    /**
     * Set the input voltage limit (VINDPM).
     *
     * @param volts 3.88–5.08 V in 80 mV steps
     * @throws IOException on bus error
     */
    public void setInputVoltageLimit(double volts) throws IOException {
        rmw(REG_VINDPM, 0x0F, stepCode(volts, 3.88, 0.08, 15, "input voltage limit"));
    }

    /**
     * Set the minimum system voltage.
     *
     * @param volts 3.2–3.9 V in 100 mV steps
     * @throws IOException on bus error
     */
    public void setMinSystemVoltage(double volts) throws IOException {
        rmw(REG_MIN_VSYS, 0x07, stepCode(volts, 3.2, 0.1, 7, "minimum system voltage"));
    }

    /**
     * Configure the charger safety timers (CHG_TIMER). Defaults: 60, 12, true, true, true.
     *
     * @param prechargeMin     pre-charge timer, 40 / 50 / 60 / 70 min
     * @param doneHours        charge-done timer, 5 / 8 / 12 / 20 h
     * @param prechargeEnabled pre-charge timer enable
     * @param doneEnabled      charge-done timer enable
     * @param slowInDpm        half-speed timers during input DPM / thermal regulation
     * @throws IOException on bus error
     */
    public void setChargeTimers(int prechargeMin, int doneHours, boolean prechargeEnabled,
                                boolean doneEnabled, boolean slowInDpm) throws IOException {
        int pre = indexOf(prechargeMin, new double[] {40, 50, 60, 70}, "precharge timer");
        int done = indexOf(doneHours, new double[] {5, 8, 12, 20}, "done timer");
        rmw(REG_CHG_TIMER, 0xF7, pre | bit(prechargeEnabled, 0x04) | (done << 4)
                | bit(doneEnabled, 0x40) | bit(slowInDpm, 0x80));
    }

    /**
     * Set the charger thermal-regulation threshold.
     *
     * @param celsius 60, 80, 100 or 120 °C
     * @throws IOException on bus error
     */
    public void setThermalRegulation(int celsius) throws IOException {
        rmw(REG_THERMAL_REG, 0x03, indexOf(celsius, new double[] {60, 80, 100, 120}, "thermal regulation"));
    }

    /**
     * Configure die over-temperature level 1 (DIE_TEMP_CTRL).
     *
     * @param level1Celsius 115, 125 or 135 °C (default 125)
     * @param detect        die temperature detection enable
     * @throws IOException on bus error
     */
    public void setDieProtection(int level1Celsius, boolean detect) throws IOException {
        int code = indexOf(level1Celsius, new double[] {115, 125, 135}, "die protection");
        rmw(REG_DIE_TEMP_CTRL, 0x07, (code << 1) | bit(detect, 1));
    }

    /**
     * Enable or disable battery detection (BAT_DETECT).
     *
     * @param enabled {@code true} to enable
     * @throws IOException on bus error
     */
    public void setBatteryDetection(boolean enabled) throws IOException {
        rmw(REG_BAT_DETECT, 0x01, bit(enabled, 1));
    }

    /**
     * Configure charging of the backup (coin) cell.
     *
     * @param enabled {@code true} to charge the backup cell
     * @param volts   termination voltage, 2.6–3.3 V in 100 mV steps (default 2.9)
     * @throws IOException on bus error
     */
    public void setBackupCharging(boolean enabled, double volts) throws IOException {
        int code = stepCode(volts, 2.6, 0.1, 7, "backup charge voltage");
        rmw(REG_BTN_BAT_VTERM, 0x07, code);
        rmw(REG_MODULE_ENABLE, 0x04, bit(enabled, 0x04));
    }

    /**
     * Configure the CHGLED pin.
     *
     * @param mode   {@code "off"}, {@code "type_a"}, {@code "type_b"} or {@code "manual"}
     * @param output for {@code "manual"}: {@code "hiz"}, {@code "blink_1hz"}, {@code "blink_4hz"} or {@code "low"}
     * @throws IOException on bus error
     */
    public void setChgled(String mode, String output) throws IOException {
        int value;
        switch (mode) {
            case "off": value = 0x00; break;
            case "type_a": value = 0x01; break;
            case "type_b": value = 0x03; break;
            case "manual": value = 0x05; break;
            default: throw new IllegalArgumentException("unknown CHGLED mode: " + mode);
        }
        int out;
        switch (output) {
            case "hiz": out = 0; break;
            case "blink_1hz": out = 1; break;
            case "blink_4hz": out = 2; break;
            case "low": out = 3; break;
            default: throw new IllegalArgumentException("unknown CHGLED output: " + output);
        }
        if (mode.equals("manual")) value |= out << 4;
        rmw(REG_CHGLED_CTRL, 0x37, value);
    }

    // --- Battery temperature ----------------------------------------------

    /**
     * Configure the TS pin (TS_CTRL).
     *
     * @param batterySensor {@code true} for the battery thermistor, {@code false} for an external
     *                      input that does not affect the charger
     * @param currentUa     injected current, 20 / 40 / 50 / 60 µA
     * @param source        current-source mode: {@code "off"}, {@code "when_adc"}, {@code "when_ts"} or {@code "always"}
     * @throws IOException on bus error
     */
    public void configureTs(boolean batterySensor, int currentUa, String source) throws IOException {
        int s;
        switch (source) {
            case "off": s = 0; break;
            case "when_adc": s = 1; break;
            case "when_ts": s = 2; break;
            case "always": s = 3; break;
            default: throw new IllegalArgumentException("unknown TS source: " + source);
        }
        int cur = indexOf(currentUa, new double[] {20, 40, 50, 60}, "TS current");
        rmw(REG_TS_CTRL, 0x1F, cur | (s << 2) | bit(!batterySensor, 0x10));
    }

    /**
     * Set the battery-temperature limits as TS pin voltages.
     *
     * @param chargeCold charge low-temperature limit in V (32 mV steps)
     * @param chargeHot  charge high-temperature limit in V (2 mV steps)
     * @param workCold   discharge low-temperature limit in V (32 mV steps)
     * @param workHot    discharge high-temperature limit in V (2 mV steps)
     * @throws IOException on bus error
     */
    public void setTsLimits(double chargeCold, double chargeHot, double workCold, double workHot)
            throws IOException {
        int a = stepCode(chargeCold, 0.0, 0.032, 255, "charge cold limit");
        int b = stepCode(chargeHot, 0.0, 0.002, 255, "charge hot limit");
        int c = stepCode(workCold, 0.0, 0.032, 255, "work cold limit");
        int d = stepCode(workHot, 0.0, 0.002, 255, "work hot limit");
        writeReg(REG_VLTF_CHG, a);
        writeReg(REG_VHTF_CHG, b);
        writeReg(REG_VLTF_WORK, c);
        writeReg(REG_VHTF_WORK, d);
    }

    /**
     * Set the TS threshold hysteresis.
     *
     * @param lowToNormal  low to normal hysteresis in V (16 mV steps, default 0.032)
     * @param highToNormal high to normal hysteresis in V (4 mV steps, default 0.004)
     * @throws IOException on bus error
     */
    public void setTsHysteresis(double lowToNormal, double highToNormal) throws IOException {
        int l2h = stepCode(lowToNormal, 0.0, 0.016, 255, "TS low hysteresis");
        int h2l = stepCode(highToNormal, 0.0, 0.004, 255, "TS high hysteresis");
        writeReg(REG_TS_HYS_L2H, l2h);
        writeReg(REG_TS_HYS_H2L, h2l);
    }

    /**
     * Configure the JEITA charging profile.
     *
     * @param enabled          JEITA enable
     * @param coolVoltage      cool threshold T2 as TS voltage in V (16 mV steps), or {@code null} to keep it
     * @param warmVoltage      warm threshold T3 as TS voltage in V (8 mV steps), or {@code null} to keep it
     * @param coolHalfCurrent  halve the charge current in the cool range (default true)
     * @param warmHalfCurrent  halve the charge current in the warm range (default false)
     * @param coolVoltageDrop  charge-voltage gears below the setting when cool, 0–2 (default 0)
     * @param warmVoltageDrop  charge-voltage gears below the setting when warm, 0–2 (default 1)
     * @throws IOException on bus error
     */
    public void configureJeita(boolean enabled, Double coolVoltage, Double warmVoltage,
                               boolean coolHalfCurrent, boolean warmHalfCurrent,
                               int coolVoltageDrop, int warmVoltageDrop) throws IOException {
        if (coolVoltageDrop < 0 || coolVoltageDrop > 2 || warmVoltageDrop < 0 || warmVoltageDrop > 2) {
            throw new IllegalArgumentException("JEITA voltage drop must be 0-2");
        }
        int cool = coolVoltage == null ? -1 : stepCode(coolVoltage, 0.0, 0.016, 255, "JEITA cool");
        int warm = warmVoltage == null ? -1 : stepCode(warmVoltage, 0.0, 0.008, 255, "JEITA warm");
        rmw(REG_JEITA_CV, 0x5F, coolVoltageDrop | (warmVoltageDrop << 2) | bit(coolHalfCurrent, 0x10)
                | bit(warmHalfCurrent, 0x40));
        if (cool >= 0) writeReg(REG_JEITA_COOL, cool);
        if (warm >= 0) writeReg(REG_JEITA_WARM, warm);
        rmw(REG_JEITA_EN, 0x01, bit(enabled, 1));
    }

    // --- Power rails ------------------------------------------------------

    /**
     * Read the state of a power rail.
     *
     * @param name {@code "dcdc1"}..{@code "dcdc4"}, {@code "aldo1"}..{@code "aldo4"},
     *             {@code "bldo1"}, {@code "bldo2"}, {@code "cpusldo"}, {@code "dldo1"} or {@code "dldo2"}
     * @return enable state and programmed voltage
     * @throws IOException on bus error
     */
    public Rail getRail(String name) throws IOException {
        int[] r = railInfo(name);
        boolean enabled = (readReg(r[0]) & (1 << r[1])) != 0;
        return new Rail(enabled, railVolts(r[3], readReg(r[2]) & r[4]));
    }

    /**
     * Set a rail voltage, rounded to the nearest step inside the rail's range.
     *
     * @param name  rail name as for {@link #getRail(String)}
     * @param volts target voltage in V; outside the rail's range is rejected
     * @throws IOException              on bus error
     * @throws IllegalArgumentException for an unknown rail or an out-of-range voltage
     */
    public void setRailVoltage(String name, double volts) throws IOException {
        int[] r = railInfo(name);
        rmw(r[2], r[4], railCode(r[3], volts, name));
    }

    /**
     * Enable or disable a rail. Disabling the rail that powers the host cuts the host's power.
     *
     * @param name    rail name as for {@link #getRail(String)}
     * @param enabled {@code true} to enable
     * @throws IOException on bus error
     */
    public void setRailEnabled(String name, boolean enabled) throws IOException {
        int[] r = railInfo(name);
        rmw(r[0], 1 << r[1], bit(enabled, 1 << r[1]));
    }

    /**
     * Configure one DCDC converter.
     *
     * @param name      {@code "dcdc1"}..{@code "dcdc4"}
     * @param alwaysPwm force PWM instead of automatic PWM/PFM
     * @param dvm       dynamic voltage scaling for dcdc2 / dcdc3, or {@code null} to keep it
     * @throws IOException on bus error
     */
    public void configureDcdc(String name, boolean alwaysPwm, Boolean dvm) throws IOException {
        int[] r = railInfo(name);
        if (r[0] != 0x80) throw new IllegalArgumentException("not a DCDC: " + name);
        if (dvm != null && !name.equals("dcdc2") && !name.equals("dcdc3")) {
            throw new IllegalArgumentException("DVM is only available on dcdc2 / dcdc3");
        }
        int b = 1 << (r[1] + 2);
        rmw(REG_DCDC_PWM, b, bit(alwaysPwm, b));
        if (dvm != null) rmw(r[2], 0x80, bit(dvm, 0x80));
    }

    /**
     * Configure settings shared by all DCDC converters.
     *
     * @param forceCcm        force continuous conduction mode
     * @param slowRamp        31.25 µs per DVM step instead of 15.625 µs
     * @param frequencySpread enable switching-frequency spread
     * @param spread100khz    spread range 100 kHz instead of 50 kHz
     * @param uvpDebounceUs   under-voltage debounce, 60 / 120 / 180 / 240 µs
     * @throws IOException on bus error
     */
    public void configureDcdcGlobal(boolean forceCcm, boolean slowRamp, boolean frequencySpread,
                                    boolean spread100khz, int uvpDebounceUs) throws IOException {
        int deb = indexOf(uvpDebounceUs, new double[] {60, 120, 180, 240}, "UVP debounce");
        rmw(REG_DCDC_EN, 0x60, bit(forceCcm, 0x40) | bit(slowRamp, 0x20));
        rmw(REG_DCDC_PWM, 0xC3, bit(frequencySpread, 0x80) | bit(spread100khz, 0x40) | deb);
    }

    // --- System control ---------------------------------------------------

    /**
     * Enable output discharge when a rail is off (COMMON_CONFIG bit 5).
     *
     * @param enabled {@code true} to enable
     * @throws IOException on bus error
     */
    public void setOffDischarge(boolean enabled) throws IOException {
        rmw(REG_COMMON_CONFIG, 0x20, bit(enabled, 0x20));
    }

    /**
     * Soft power-off: every output except RTCLDO turns off.
     *
     * @throws IOException on bus error
     */
    public void powerOff() throws IOException {
        command(REG_COMMON_CONFIG, 0x01);
    }

    /**
     * Restart the system (power-off / power-on).
     *
     * @throws IOException on bus error
     */
    public void restart() throws IOException {
        command(REG_COMMON_CONFIG, 0x02);
    }

    /**
     * Enter sleep; the host disables / re-voltages rails first.
     *
     * @throws IOException on bus error
     */
    public void sleep() throws IOException {
        command(REG_SLEEP_CTRL, 0x01);
    }

    /**
     * Wake up from sleep.
     *
     * @throws IOException on bus error
     */
    public void wakeup() throws IOException {
        command(REG_SLEEP_CTRL, 0x02);
    }

    /**
     * Configure sleep / wake-up behaviour (SLEEP_CTRL). Defaults: false, true, false.
     *
     * @param wakeOnIrq       IRQ pin held low for more than 16 ms wakes the PMU
     * @param pwrokLow        PWROK low during wake-up
     * @param restoreVoltages wake with the voltages from before sleep
     * @throws IOException on bus error
     */
    public void configureSleep(boolean wakeOnIrq, boolean pwrokLow, boolean restoreVoltages)
            throws IOException {
        rmw(REG_SLEEP_CTRL, 0x1C, bit(wakeOnIrq, 0x10) | bit(pwrokLow, 0x08) | bit(restoreVoltages, 0x04));
    }

    /**
     * Read the power-on source flags.
     *
     * @return PWRON_STATUS (bit 0 key, 1 IRQ pin, 2 VBUS, 3 battery &gt; 3.3 V, 4 battery inserted, 5 EN mode)
     * @throws IOException on bus error
     */
    public int powerOnSource() throws IOException {
        return readReg(REG_PWRON_STATUS);
    }

    /**
     * Read the power-off source flags.
     *
     * @return PWROFF_STATUS (bit 0 key, 1 software, 2 EN low, 3 VSYS UV, 4 VBUS OV, 5 DCDC UV, 6 DCDC OV, 7 die over-temperature)
     * @throws IOException on bus error
     */
    public int powerOffSource() throws IOException {
        return readReg(REG_PWROFF_STATUS);
    }

    /**
     * Configure the power-key timing and actions. Defaults: 512, 6, 1.5, true, false, false.
     *
     * @param onMs           hold time to power on, 128 / 512 / 1000 / 2000 ms
     * @param offS           hold time to power off, 4 / 6 / 8 / 10 s
     * @param irqS           hold time for the long-press IRQ, 1 / 1.5 / 2 / 2.5 s
     * @param longPressOff   power off when the key is held for {@code offS}
     * @param restartInstead restart instead of powering off
     * @param forceOff16s    holding the key for 16 s shuts the PMIC down
     * @throws IOException on bus error
     */
    public void configurePowerKey(int onMs, double offS, double irqS, boolean longPressOff,
                                  boolean restartInstead, boolean forceOff16s) throws IOException {
        int on = indexOf(onMs, new double[] {128, 512, 1000, 2000}, "power key on time");
        int off = indexOf(offS, new double[] {4, 6, 8, 10}, "power key off time");
        int irq = indexOf(irqS, new double[] {1, 1.5, 2, 2.5}, "power key irq time");
        rmw(REG_PWRKEY_LEVEL, 0x3F, on | (off << 2) | (irq << 4));
        rmw(REG_PWROFF_EN, 0x03, bit(longPressOff, 0x02) | bit(restartInstead, 0x01));
        rmw(REG_COMMON_CONFIG, 0x04, bit(forceOff16s, 0x04));
    }

    /**
     * Configure the power-off sources and thresholds. Defaults: true, 2.6, true, 0x0F.
     *
     * @param dieOverTemp power off on die over-temperature level 2 (145 °C)
     * @param offVoltage  system power-off threshold, 2.6–3.3 V in 100 mV steps
     * @param dcdcOvp     DCDC over-voltage turns the PMIC off
     * @param dcdcUvp     mask of DCDC1–4 whose under-voltage turns the PMIC off (bits 0–3)
     * @throws IOException on bus error
     */
    public void configurePowerOff(boolean dieOverTemp, double offVoltage, boolean dcdcOvp, int dcdcUvp)
            throws IOException {
        int code = stepCode(offVoltage, 2.6, 0.1, 7, "power-off voltage");
        rmw(REG_PWROFF_EN, 0x04, bit(dieOverTemp, 0x04));
        rmw(REG_VOFF, 0x07, code);
        rmw(REG_DCDC_PROT, 0x2F, bit(dcdcOvp, 0x20) | (dcdcUvp & 0x0F));
    }

    /**
     * Configure the PWROK output. Defaults: true, 8, true, false, false.
     *
     * @param check              check PWROK after all outputs are valid
     * @param delayMs            PWROK delay, 8 / 16 / 32 / 64 ms
     * @param offDelay           4 ms power-off delay after PWROK disable
     * @param reverseOffSequence power off in reverse start-up order
     * @param restartOnLow       PWROK pulled low restarts the system
     * @throws IOException on bus error
     */
    public void configurePwrok(boolean check, int delayMs, boolean offDelay,
                               boolean reverseOffSequence, boolean restartOnLow) throws IOException {
        int delay = indexOf(delayMs, new double[] {8, 16, 32, 64}, "PWROK delay");
        rmw(REG_PWROK_CTRL, 0x1F, delay | bit(reverseOffSequence, 0x04) | bit(offDelay, 0x08) | bit(check, 0x10));
        rmw(REG_COMMON_CONFIG, 0x08, bit(restartOnLow, 0x08));
    }

    /**
     * Turn the BATFET on when powered off on battery only (BATFET_CTRL bit 3).
     *
     * @param enabled {@code true} to enable
     * @throws IOException on bus error
     */
    public void setBatfetOffEnable(boolean enabled) throws IOException {
        rmw(REG_BATFET_CTRL, 0x08, bit(enabled, 0x08));
    }

    /**
     * Configure fast power-on / wake-up and, optionally, the per-rail start sequence.
     *
     * @param poweron  fast power-on enable
     * @param wakeup   fast wake-up enable
     * @param sequence rail name to start-sequence code 0–2 (a {@code null} value disables the
     *                 rail), or {@code null} to leave the sequence untouched
     * @throws IOException on bus error
     */
    public void configureFastPoweron(boolean poweron, boolean wakeup, Map<String, Integer> sequence)
            throws IOException {
        if (sequence != null) {
            for (Map.Entry<String, Integer> e : sequence.entrySet()) {
                if (!FAST_RAILS.containsKey(e.getKey())) {
                    throw new IllegalArgumentException("unknown rail: " + e.getKey());
                }
                Integer code = e.getValue();
                if (code != null && (code < 0 || code > 2)) {
                    throw new IllegalArgumentException("start-sequence code must be 0-2 or null");
                }
            }
            for (Map.Entry<String, Integer> e : sequence.entrySet()) {
                int[] f = FAST_RAILS.get(e.getKey());
                int code = e.getValue() == null ? 3 : e.getValue();
                rmw(REG_FAST_PWRON0 + f[0], 3 << f[1], code << f[1]);
            }
        }
        rmw(REG_FAST_PWRON0 + 3, 0xC0, bit(poweron, 0x80) | bit(wakeup, 0x40));
    }

    /**
     * Configure the watchdog.
     *
     * @param enabled  watchdog enable
     * @param timeoutS 1, 2, 4, 8, 16, 32, 64 or 128 s
     * @param action   {@code "irq"}, {@code "reset"}, {@code "reset_pwrok"} or {@code "power_cycle"}
     * @throws IOException on bus error
     */
    public void configureWatchdog(boolean enabled, int timeoutS, String action) throws IOException {
        int a;
        switch (action) {
            case "irq": a = 0; break;
            case "reset": a = 1; break;
            case "reset_pwrok": a = 2; break;
            case "power_cycle": a = 3; break;
            default: throw new IllegalArgumentException("unknown watchdog action: " + action);
        }
        int code = indexOf(timeoutS, new double[] {1, 2, 4, 8, 16, 32, 64, 128}, "watchdog timeout");
        rmw(REG_WATCHDOG_CTRL, 0x37, code | (a << 4));
        rmw(REG_MODULE_ENABLE, 0x01, bit(enabled, 1));
    }

    /**
     * Clear (feed) the watchdog (WATCHDOG_CTRL bit 3).
     *
     * @throws IOException on bus error
     */
    public void feedWatchdog() throws IOException {
        command(REG_WATCHDOG_CTRL, 0x08);
    }

    // --- Fuel gauge -------------------------------------------------------

    /**
     * Set the low-battery warning levels (LOWBAT_WARN).
     *
     * @param level2 warning level 2, 5–20 % (default 10)
     * @param level1 warning level 1, 0–15 % (default 1)
     * @throws IOException on bus error
     */
    public void configureBatteryWarning(int level2, int level1) throws IOException {
        if (level2 < 5 || level2 > 20 || level1 < 0 || level1 > 15) {
            throw new IllegalArgumentException("battery warning levels out of range");
        }
        writeReg(REG_LOWBAT_WARN, ((level2 - 5) << 4) | level1);
    }

    /**
     * Enable or disable the fuel gauge (MODULE_ENABLE bit 3).
     *
     * @param enabled {@code true} to enable
     * @throws IOException on bus error
     */
    public void setGaugeEnabled(boolean enabled) throws IOException {
        rmw(REG_MODULE_ENABLE, 0x08, bit(enabled, 0x08));
    }

    /**
     * Reset the fuel gauge.
     *
     * @param full {@code true} for the deeper reset that also clears gauge data beyond the registers
     * @throws IOException on bus error
     */
    public void resetGauge(boolean full) throws IOException {
        command(REG_GAUGE_RESET, full ? 0x04 : 0x08);
    }

    // --- GPIO1 and data buffer --------------------------------------------

    /**
     * Drive the open-drain GPIO1 output.
     *
     * @param low {@code true} to drive low, {@code false} for Hi-Z
     * @throws IOException on bus error
     */
    public void setGpio1(boolean low) throws IOException {
        rmw(REG_GPIO1_CTRL, 0x0C, bit(low, 0x04));
    }

    /**
     * Read one of the four general-purpose bytes.
     *
     * @param index 0–3
     * @return the stored byte
     * @throws IOException on bus error
     */
    public int readDataBuffer(int index) throws IOException {
        if (index < 0 || index > 3) throw new IllegalArgumentException("data buffer index must be 0-3");
        return readReg(REG_DATA_BUFFER0 + index);
    }

    /**
     * Write one of the four general-purpose bytes (survives soft restarts, not a power-on reset).
     *
     * @param index 0–3
     * @param value byte to store
     * @throws IOException on bus error
     */
    public void writeDataBuffer(int index, int value) throws IOException {
        if (index < 0 || index > 3) throw new IllegalArgumentException("data buffer index must be 0-3");
        writeReg(REG_DATA_BUFFER0 + index, value);
    }

    // --- Interrupts -------------------------------------------------------

    /**
     * Enable interrupt sources (IRQ_EN0..2 read-modify-write).
     *
     * @param source bitwise OR of {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public void enableInterrupt(int source) throws IOException {
        for (int k = 0; k < 3; k++) {
            int bits = (source >> (8 * k)) & 0xFF;
            if (bits != 0) rmw(REG_IRQ_EN0 + k, bits, bits);
        }
    }

    /**
     * Disable interrupt sources.
     *
     * @param source bitwise OR of {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public void disableInterrupt(int source) throws IOException {
        for (int k = 0; k < 3; k++) {
            int bits = (source >> (8 * k)) & 0xFF;
            if (bits != 0) rmw(REG_IRQ_EN0 + k, bits, 0);
        }
    }

    /**
     * Read and clear the interrupt status. Writes back exactly the bits it saw set, so an event
     * arriving between the read and the write is not lost.
     *
     * @return 24-bit status: IRQ_STATUS0 in bits 7:0, IRQ_STATUS1 in 15:8, IRQ_STATUS2 in 23:16
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int status = 0;
        for (int k = 0; k < 3; k++) {
            int v = readReg(REG_IRQ_STATUS0 + k);
            if (v != 0) writeReg(REG_IRQ_STATUS0 + k, v);
            status |= v << (8 * k);
        }
        return status;
    }

    /**
     * Subscribe to IRQ pin assertions. Uses {@code connection.intPin()} (falling edge) when
     * wired, otherwise a 5&nbsp;ms polling thread.
     *
     * @param callback called with the 24-bit status
     */
    public synchronized void onInterrupt(IntConsumer callback) {
        offInterrupt();
        this.callback = callback;
        InputPin pin = connection.intPin();
        if (pin != null) {
            subscribedPin = pin;
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "axp2101-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribe from IRQ pin assertions. */
    public synchronized void offInterrupt() {
        callback = null;
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler);
            subscribedPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            int status = pollInterrupt();
            if (status != 0) cb.accept(status);
        } catch (IOException e) {
            // keep delivering: a transient bus error must not kill the polling thread
        }
    }
}
