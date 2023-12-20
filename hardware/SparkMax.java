// Copyright (c) LASA Robotics and other contributors
// Open Source Software; you can modify and/or share it under the terms of
// the MIT license file in the root directory of this project.

package org.lasarobotics.hardware;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.lasarobotics.utils.GlobalConstants;
import org.lasarobotics.utils.SparkPIDConfig;
import org.littletonrobotics.junction.AutoLog;
import org.littletonrobotics.junction.Logger;

import com.revrobotics.AbsoluteEncoder;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMax.ControlType;
import com.revrobotics.CANSparkMax.ExternalFollower;
import com.revrobotics.CANSparkMax.IdleMode;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;
import com.revrobotics.MotorFeedbackSensor;
import com.revrobotics.REVLibError;
import com.revrobotics.REVPhysicsSim;
import com.revrobotics.SparkMaxAbsoluteEncoder;
import com.revrobotics.SparkMaxAnalogSensor;
import com.revrobotics.SparkMaxLimitSwitch;
import com.revrobotics.SparkMaxPIDController;

import edu.wpi.first.math.filter.Debouncer;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.wpilibj.Timer;

/** REV Spark Max */
public class SparkMax implements LoggableHardware, AutoCloseable {
  /** Spark Max ID */
  public static class ID {
    public final String name;
    public final int deviceID;

    /**
     * Spark Max ID
     * @param name Device name for logging
     * @param deviceID CAN ID
     */
    public ID(String name, int deviceID) {
      this.name = name;
      this.deviceID = deviceID;
    }
  }

  /** Feedback sensor */
  public enum FeedbackSensor {
    NEO_ENCODER, ANALOG, THROUGH_BORE_ENCODER;
  }

  /**
   * Spark Max sensor inputs
   */
  @AutoLog
  public static class SparkMaxInputs {
    public double encoderPosition = 0.0;
    public double encoderVelocity = 0.0;
    public double analogPosition = 0.0;
    public double analogVelocity = 0.0;
    public double absoluteEncoderPosition = 0.0;
    public double absoluteEncoderVelocity = 0.0;
    public boolean forwardLimitSwitch = false;
    public boolean reverseLimitSwitch = false;
  }

  private static final int PID_SLOT = 0;
  private static final int BURN_FLASH_ATTEMPTS = 5;
  private static final double MAX_VOLTAGE = 12.0;
  private static final double SMOOTH_MOTION_DEBOUNCE_TIME = 0.1;
  private static final String VALUE_LOG_ENTRY = "/OutputValue";
  private static final String MODE_LOG_ENTRY = "/OutputMode";
  private static final String CURRENT_LOG_ENTRY = "/Current";
  private static final String MOTION_LOG_ENTRY = "/SmoothMotion";


  private CANSparkMax m_spark;

  private ID m_id;
  private SparkMaxInputsAutoLogged m_inputs;

  private boolean m_isSmoothMotionEnabled;
  private Debouncer m_smoothMotionFinishedDebouncer;
  private TrapezoidProfile.State m_desiredState;
  private TrapezoidProfile.State m_smoothMotionState;
  private Supplier<TrapezoidProfile.State> m_currentStateSupplier;
  private Function<TrapezoidProfile.State, Double> m_feedforwardSupplier;

  private TrapezoidProfile m_motionProfile;
  private TrapezoidProfile.Constraints m_motionConstraint;
  private SparkPIDConfig m_config;
  private FeedbackSensor m_feedbackSensor;
  private SparkMaxLimitSwitch.Type m_limitSwitchType = SparkMaxLimitSwitch.Type.kNormallyOpen;

  /**
   * Create a Spark Max with built-in logging that is unit-testing friendly and
   * @param id Spark Max ID
   * @param motorType The motor type connected to the controller
   */
  public SparkMax(ID id, MotorType motorType) {
    this.m_id = id;
    this.m_spark = new CANSparkMax(id.deviceID, motorType);
    this.m_inputs = new SparkMaxInputsAutoLogged();
    this.m_isSmoothMotionEnabled = false;

    m_spark.restoreFactoryDefaults();
    m_spark.enableVoltageCompensation(MAX_VOLTAGE);
  }

  /**
   * Create a Spark Max with built-in logging that is unit-testing friendly and configure PID
   * @param id Spark Max ID
   * @param motorType The motor type connected to the controller
   * @param config PID config for spark max
   * @param feedbackSensor Feedback device to use for Spark PID
   */
  public SparkMax(ID id, MotorType motorType, SparkPIDConfig config, FeedbackSensor feedbackSensor) {
    this.m_id = id;
    this.m_spark = new CANSparkMax(id.deviceID, motorType);
    this.m_inputs = new SparkMaxInputsAutoLogged();
    this.m_isSmoothMotionEnabled = false;

    m_spark.restoreFactoryDefaults();
    m_spark.enableVoltageCompensation(MAX_VOLTAGE);

    this.m_config = config;
    this.m_smoothMotionFinishedDebouncer = new Debouncer(SMOOTH_MOTION_DEBOUNCE_TIME);
    this.m_desiredState = new TrapezoidProfile.State();
    this.m_smoothMotionState = new TrapezoidProfile.State();
    this.m_feedforwardSupplier = (motionProfileState) -> 0.0;
    this.m_currentStateSupplier = () -> new TrapezoidProfile.State(getInputs().encoderPosition, getInputs().encoderVelocity);
    initializeSparkPID(m_config, feedbackSensor);
  }

  /**
   * Log output values
   * @param value Value that was set
   * @param ctrl Control mode that was used
   */
  private void logOutputs(double value, ControlType ctrl) {
    Logger.recordOutput(m_id.name + VALUE_LOG_ENTRY, value);
    Logger.recordOutput(m_id.name + MODE_LOG_ENTRY, ctrl.toString());
    Logger.recordOutput(m_id.name + CURRENT_LOG_ENTRY, m_spark.getOutputCurrent());
    Logger.recordOutput(m_id.name + MOTION_LOG_ENTRY, m_isSmoothMotionEnabled);
  }

  /**
   * Get the position of the motor encoder. This returns the native units of 'rotations' by default, and can
   * be changed by a scale factor using setPositionConversionFactor().
   * @return Number of rotations of the motor
   */
  private double getEncoderPosition() {
    return m_spark.getEncoder().getPosition();
  }

  /**
   * Get the velocity of the motor encoder. This returns the native units of 'RPM' by default, and can be
   * changed by a scale factor using setVelocityConversionFactor().
   * @return Number the RPM of the motor
   */
  private double getEncoderVelocity() {
    return m_spark.getEncoder().getVelocity();
  }

  /**
   * Returns an object for interfacing with a connected analog sensor.
   * @return An object for interfacing with a connected analog sensor
   */
  private SparkMaxAnalogSensor getAnalog() {
    return m_spark.getAnalog(SparkMaxAnalogSensor.Mode.kAbsolute);
  }

  /**
   * Get position of the analog sensor. This returns the native units 'volt' by default, and can
   * be changed by a scale factor using setPositionConversionFactor().
   * @return Volts on the sensor
   */
  private double getAnalogPosition() {
    return getAnalog().getPosition();
  }

  /**
   * Get the velocity of the analog sensor. This returns the native units of 'volts per second' by default, and can be
   * changed by a scale factor using setVelocityConversionFactor().
   * @return Volts per second on the sensor
   */
  private double getAnalogVelocity() {
    return getAnalog().getVelocity();
  }

  /**
   * Returns an object for interfacing with a connected absolute encoder.
   * @return An object for interfacing with a connected absolute encoder
   */
  private AbsoluteEncoder getAbsoluteEncoder() {
    return m_spark.getAbsoluteEncoder(SparkMaxAbsoluteEncoder.Type.kDutyCycle);
  }

  /**
   * Get position of the absolute encoder. This returns the native units 'rotations' by default, and can
   * be changed by a scale factor using setPositionConversionFactor().
   * @return Number of rotations of the motor
   */
  private double getAbsoluteEncoderPosition() {
    return getAbsoluteEncoder().getPosition();
  }

  /**
   * Get the velocity of the absolute encoder. This returns the native units of 'RPM' by default, and can be
   * changed by a scale factor using setVelocityConversionFactor().
   * @return Number the RPM of the motor
   */
  private double getAbsoluteEncoderVelocity() {
    return getAbsoluteEncoder().getVelocity();
  }

  /**
   * Returns an object for interfacing with the forward limit switch connected to the appropriate
   * pins on the data port.
   *
   * <p>This call will disable support for the alternate encoder.
   *
   * @param switchType Whether the limit switch is normally open or normally closed.
   * @return An object for interfacing with the forward limit switch.
   */
  private SparkMaxLimitSwitch getForwardLimitSwitch() {
    return m_spark.getForwardLimitSwitch(m_limitSwitchType);
  }

  /**
   * Returns an object for interfacing with the reverse limit switch connected to the appropriate
   * pins on the data port.
   *
   * <p>This call will disable support for the alternate encoder.
   *
   * @param switchType Whether the limit switch is normally open or normally closed.
   * @return An object for interfacing with the reverse limit switch.
   */
  private SparkMaxLimitSwitch getReverseLimitSwitch() {
    return m_spark.getReverseLimitSwitch(m_limitSwitchType);
  }

  /**
   * Update sensor input readings
   */
  private void updateInputs() {
    m_inputs.analogPosition = getAnalogPosition();
    m_inputs.analogVelocity = getAnalogVelocity();
    m_inputs.absoluteEncoderPosition = getAbsoluteEncoderPosition();
    m_inputs.absoluteEncoderVelocity = getAbsoluteEncoderVelocity();
    m_inputs.forwardLimitSwitch = getForwardLimitSwitch().isPressed();
    m_inputs.reverseLimitSwitch = getReverseLimitSwitch().isPressed();

    if (getMotorType() == MotorType.kBrushed) return;
    m_inputs.encoderPosition = getEncoderPosition();
    m_inputs.encoderVelocity = getEncoderVelocity();
  }

  /**
   * Handle smooth motion
   */
  private void handleSmoothMotion() {
    if (!m_isSmoothMotionEnabled) return;

    m_smoothMotionState = m_motionProfile.calculate(GlobalConstants.ROBOT_LOOP_PERIOD, m_smoothMotionState, m_currentStateSupplier.get());
    set(
      m_smoothMotionState.position,
      ControlType.kPosition,
      m_feedforwardSupplier.apply(m_smoothMotionState),
      SparkMaxPIDController.ArbFFUnits.kVoltage
    );

    m_isSmoothMotionEnabled = !isSmoothMotionFinished();
  }

  private void checkStatus(REVLibError status, String errorMessage) {
    if (status != REVLibError.kOk)
      System.out.println(String.join(" ", m_id.name, errorMessage, "-", status.toString()));
  }

  /**
   * Attempt to burn settings to flash and check if specified parameter is set correctly
   * @param parameterCheckSupplier Method to check for parameter in question
   * @return {@link REVLibError#kOk} if successful
   */
  private REVLibError burnFlash(BooleanSupplier parameterCheckSupplier) {
    REVLibError status = REVLibError.kError;
    for (int i = 0; i < BURN_FLASH_ATTEMPTS; i++) {
      status = burnFlash();
      if (parameterCheckSupplier.getAsBoolean() && status == REVLibError.kOk) break;
    }

    return status;
  }

  /**
   * Writes all settings to flash.
   * @return {@link REVLibError#kOk} if successful
   */
  public REVLibError burnFlash() {
    Timer.delay(0.5);
    REVLibError status = m_spark.burnFlash();
    Timer.delay(0.5);

    return status;
  }

  /**
   * Restore motor controller parameters to factory defaults until the next controller reboot
   * @return {@link REVLibError#kOk} if successful
   */
  public REVLibError restoreFactoryDefaults() {
    REVLibError status;
    m_spark.restoreFactoryDefaults();
    status = burnFlash(() -> true);

    checkStatus(status, "Restore factory defaults failure");

    return status;
  }

  /**
   * Add motor to simulation
   * @param motor Motor that is connected to this Spark Max
   */
  public void addToSimulation(DCMotor motor) {
    REVPhysicsSim.getInstance().addSparkMax(m_spark, motor);
  }

  /**
   * Call this method periodically
   */
  @Override
  public void periodic() {
    updateInputs();
    Logger.processInputs(m_id.name, m_inputs);

    handleSmoothMotion();
  }

  /**
   * Get latest sensor input data
   * @return Latest sensor data
   */
  @Override
  public SparkMaxInputsAutoLogged getInputs() {
    return m_inputs;
  }

  /**
   * Get device ID
   * @return Device ID
   */
  public ID getID() {
    return m_id;
  }

  /**
   * Get motor type
   * @return Motor type
   */
  public MotorType getMotorType() {
    return m_spark.getMotorType();
  }

  /**
   * Check if motion is complete
   * @return True if smooth motion is complete
   */
  public boolean isSmoothMotionFinished() {
    return m_smoothMotionFinishedDebouncer.calculate(
      Math.abs(m_currentStateSupplier.get().position - m_desiredState.position) < m_config.getTolerance()
    );
  }

  /**
   * Initializes Spark Max PID
   * @param config Configuration to apply
   * @param feedbackSensor Feedback device to use for Spark PID
   * @param forwardLimitSwitch Enable forward limit switch
   * @param reverseLimitSwitch Enable reverse limit switch
   */
  public void initializeSparkPID(SparkPIDConfig config, FeedbackSensor feedbackSensor,
                                 boolean forwardLimitSwitch, boolean reverseLimitSwitch) {
    if (getMotorType() == MotorType.kBrushed && feedbackSensor == FeedbackSensor.NEO_ENCODER)
      throw new IllegalArgumentException("NEO encoder cannot be used in brushed motor mode!");

    m_config = config;
    m_feedbackSensor = feedbackSensor;
    m_smoothMotionFinishedDebouncer = new Debouncer(SMOOTH_MOTION_DEBOUNCE_TIME);
    m_desiredState = new TrapezoidProfile.State();
    m_smoothMotionState = new TrapezoidProfile.State();
    m_feedforwardSupplier = (motionProfileState) -> 0.0;

    MotorFeedbackSensor selectedSensor;
    switch (m_feedbackSensor) {
      case ANALOG:
        selectedSensor = m_spark.getAnalog(SparkMaxAnalogSensor.Mode.kAbsolute);
        m_currentStateSupplier = () -> new TrapezoidProfile.State(getInputs().analogPosition, getInputs().analogVelocity);
        break;
      case THROUGH_BORE_ENCODER:
        selectedSensor = m_spark.getAbsoluteEncoder(SparkMaxAbsoluteEncoder.Type.kDutyCycle);
        m_currentStateSupplier = () -> new TrapezoidProfile.State(getInputs().absoluteEncoderPosition, getInputs().absoluteEncoderVelocity);
        break;
      case NEO_ENCODER:
      default:
        selectedSensor = m_spark.getEncoder();
        m_currentStateSupplier = () -> new TrapezoidProfile.State(getInputs().encoderPosition, getInputs().encoderVelocity);
        break;
    }

    m_config.initializeSparkPID(m_spark, selectedSensor, forwardLimitSwitch, reverseLimitSwitch);
    burnFlash();
  }

  /**
   * Initializes Spark Max PID
   * <p>
   * Calls {@link SparkMax#initializeSparkPID(SparkPIDConfig, FeedbackSensor, boolean, boolean)} with no limit switches
   * @param config Configuration to apply
   * @param feedbackSensor Feedback device to use for Spark PID
   */
  public void initializeSparkPID(SparkPIDConfig config, FeedbackSensor feedbackSensor) {
    initializeSparkPID(config, feedbackSensor, false, false);
  }

  /**
   * Slave Spark Max to another
   * @param master Spark Max to follow
   * @param invert Set slave to output opposite of the master
   */
  public void follow(SparkMax master, boolean invert) {
    REVLibError status;
    m_spark.follow(ExternalFollower.kFollowerSparkMax, master.getID().deviceID, invert);
    status = burnFlash(() -> m_spark.isFollower() && m_spark.getInverted() == invert);

    checkStatus(status, "Set motor master failure");
  }

  /**
   * Slave Spark Max to another
   * @param master Spark Max to follow
   */
  public void follow(SparkMax master) {
    follow(master, false);
  }

  /**
   * Common interface for inverting direction of a speed controller.
   *
   * <p>This call has no effect if the controller is a slave. To invert a slave, see the
   * {@link SparkMax#follow(SparkMax, boolean)} method.
   *
   * @param isInverted The state of inversion, true is inverted.
   */
  public void setInverted(boolean isInverted) {
    REVLibError status;
    m_spark.setInverted(isInverted);
    status = burnFlash(() -> m_spark.getInverted() == isInverted);

    checkStatus(status, "Set motor inversion failure");
  }

  /**
   * Set motor output duty cycle
   * @param value Value to set [-1.0, +1.0]
   */
  public void set(double value) {
    set(value, ControlType.kDutyCycle);
  }

  /**
   * Set motor output value
   * @param value Value to set
   * @param ctrl Desired control mode
   */
  public void set(double value, ControlType ctrl) {
    m_spark.getPIDController().setReference(value, ctrl);
    logOutputs(value, ctrl);
  }

  /**
   * Set motor output value with arbitrary feed forward
   * @param value Value to set
   * @param ctrl Desired control mode
   * @param arbFeedforward Feed forward value
   * @param arbFFUnits Feed forward units
   */
  public void set(double value, ControlType ctrl, double arbFeedforward, SparkMaxPIDController.ArbFFUnits arbFFUnits) {
    m_spark.getPIDController().setReference(value, ctrl, PID_SLOT, arbFeedforward, arbFFUnits);
    logOutputs(value, ctrl);
  }

  /**
   * Change the limit switch type
   * @param type The desired limit switch type
   */
  public void setLimitSwitchType(SparkMaxLimitSwitch.Type type) {
    m_limitSwitchType = type;
  }

  /**
   * Set the conversion factor for position of the encoder. Multiplied by the native output units to
   * give you position.
   * @param sensor Sensor to set conversion factor for
   * @param factor The conversion factor to multiply the native units by
   */
  public void setPositionConversionFactor(FeedbackSensor sensor, double factor) {
    REVLibError status;
    BooleanSupplier parameterCheckSupplier;
    switch (sensor) {
      case NEO_ENCODER:
        m_spark.getEncoder().setPositionConversionFactor(factor);
        parameterCheckSupplier = () -> m_spark.getEncoder().getPositionConversionFactor() == factor;
        break;
      case ANALOG:
        getAnalog().setPositionConversionFactor(factor);
        parameterCheckSupplier = () -> getAnalog().getPositionConversionFactor() == factor;
        break;
      case THROUGH_BORE_ENCODER:
        getAbsoluteEncoder().setPositionConversionFactor(factor);
        parameterCheckSupplier = () -> getAbsoluteEncoder().getPositionConversionFactor() == factor;
        break;
      default:
        parameterCheckSupplier = () -> true;
        break;
    }

    status = burnFlash(parameterCheckSupplier);
    checkStatus(status, "Set position conversion factor failure");
  }

  /**
   * Set the conversion factor for velocity of the encoder. Multiplied by the native output units to
   * give you velocity.
   * @param sensor Sensor to set conversion factor for
   * @param factor The conversion factor to multiply the native units by
   */
  public void setVelocityConversionFactor(FeedbackSensor sensor, double factor) {
    REVLibError status;
    BooleanSupplier parameterCheckSupplier;
    switch (sensor) {
      case NEO_ENCODER:
        m_spark.getEncoder().setVelocityConversionFactor(factor);
        parameterCheckSupplier = () -> m_spark.getEncoder().getVelocityConversionFactor() == factor;
        break;
      case ANALOG:
        getAnalog().setVelocityConversionFactor(factor);
        parameterCheckSupplier = () -> getAnalog().getVelocityConversionFactor() == factor;
        break;
      case THROUGH_BORE_ENCODER:
        getAbsoluteEncoder().setVelocityConversionFactor(factor);
        parameterCheckSupplier = () -> getAnalog().getVelocityConversionFactor() == factor;
        break;
      default:
        parameterCheckSupplier = () -> true;
        break;
    }

    status = burnFlash(parameterCheckSupplier);
    checkStatus(status, "Set velocity conversion factor failure");
  }

  /**
   * Execute a smooth motion to desired position
   * @param value The target value for the motor
   * @param motionConstraint The constraints for the motor
   * @param feedforwardSupplier Lambda function to calculate feed forward
   */
  public void smoothMotion(double value, TrapezoidProfile.Constraints motionConstraint, Function<TrapezoidProfile.State, Double> feedforwardSupplier) {
    m_isSmoothMotionEnabled = true;
    m_feedforwardSupplier = feedforwardSupplier;
    m_motionConstraint = motionConstraint;
    m_desiredState = new TrapezoidProfile.State(value, 0.0);
    m_motionProfile = new TrapezoidProfile(m_motionConstraint);
    m_smoothMotionState = m_desiredState;
  }

  /**
   * Execute a smooth motion to desired position
   * @param value The target value for the motor
   * @param motionConstraint The constraints for the motor
   */
  public void smoothMotion(double value, TrapezoidProfile.Constraints motionConstraint) {
    smoothMotion(value, motionConstraint, (motionProfileState) -> 0.0);
  }

  /**
   * Reset NEO built-in encoder
   */
  public void resetEncoder() {
    m_spark.getEncoder().setPosition(0.0);
    System.out.println(String.join(" ", m_id.name, "Encoder reset!"));
  }

  /**
   * Enable PID wrapping for closed loop position control
   * @param minInput Value of the min input for position
   * @param maxInput Value of max input for position
   */
  public void enablePIDWrapping(double minInput, double maxInput) {
    REVLibError status;
    m_spark.getPIDController().setPositionPIDWrappingEnabled(true);
    m_spark.getPIDController().setPositionPIDWrappingMinInput(minInput);
    m_spark.getPIDController().setPositionPIDWrappingMaxInput(maxInput);

    status = burnFlash(() ->
      m_spark.getPIDController().getPositionPIDWrappingEnabled() == true &&
      m_spark.getPIDController().getPositionPIDWrappingMinInput() == minInput &&
      m_spark.getPIDController().getPositionPIDWrappingMaxInput() == maxInput
    );

    checkStatus(status, "Enable position PID wrapping failure");
  }

  /**
   * Disable PID wrapping for close loop position control
   */
  public void disablePIDWrapping() {
    REVLibError status;
    m_spark.getPIDController().setPositionPIDWrappingEnabled(false);

    status = burnFlash(() -> m_spark.getPIDController().getPositionPIDWrappingEnabled() == false);
    checkStatus(status, "Disable position PID wrapping failure");
  }

  /**
   * Sets the idle mode setting for the SPARK MAX.
   * @param mode Idle mode (coast or brake).
   */
  public void setIdleMode(IdleMode mode) {
    m_spark.setIdleMode(mode);
  }

  /**
   * Sets the current limit in Amps.
   *
   * <p>The motor controller will reduce the controller voltage output to avoid surpassing this
   * limit. This limit is enabled by default and used for brushless only. This limit is highly
   * recommended when using the NEO brushless motor.
   *
   * <p>The NEO Brushless Motor has a low internal resistance, which can mean large current spikes
   * that could be enough to cause damage to the motor and controller. This current limit provides a
   * smarter strategy to deal with high current draws and keep the motor and controller operating in
   * a safe region.
   *
   * @param limit The current limit in Amps.
   */
  public void setSmartCurrentLimit(int limit) {
    m_spark.setSmartCurrentLimit(limit);
  }

  /**
   * Stops motor movement. Motor can be moved again by calling set without having to re-enable the
   * motor.
   */
  public void stopMotor() {
    m_spark.stopMotor();
    logOutputs(0.0, ControlType.kDutyCycle);
  }

  /**
   * Closes the Spark Max controller
   */
  @Override
  public void close() {
    m_spark.close();
  }
}
