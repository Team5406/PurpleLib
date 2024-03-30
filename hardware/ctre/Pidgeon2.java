// Copyright (c) LASA Robotics and other contributors
// Open Source Software; you can modify and/or share it under the terms of
// the MIT license file in the root directory of this project.

package org.lasarobotics.hardware.ctre;

import org.lasarobotics.hardware.LoggableHardware;
import org.lasarobotics.utils.GlobalConstants;
import org.littletonrobotics.junction.AutoLog;
import org.littletonrobotics.junction.Logger;

import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.configs.MountPoseConfigs;
import com.ctre.phoenix6.configs.Pigeon2Configuration;
import com.ctre.phoenix6.hardware.Pigeon2;

import edu.wpi.first.hal.SimDouble;
import edu.wpi.first.hal.simulation.SimDeviceDataJNI;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.units.Angle;
import edu.wpi.first.units.Measure;
import edu.wpi.first.units.Units;
import edu.wpi.first.units.Velocity;

/** CTRE Pidgeon 2.0 */
public class Pidgeon2 implements LoggableHardware, AutoCloseable {


  /** Pidgeon ID */
  public static class ID {
    public final String name;
    public final PhoenixCANBus bus;
    public final int deviceID;

    /**
     * Pidgeon2 ID
     * @param name Device name for logging
     * @param bus CAN bus
     * @param deviceID CAN ID
     */
    public ID(String name, PhoenixCANBus bus, int deviceID) {
      this.name = name;
      this.bus = bus;
      this.deviceID = deviceID;
    }
  }

  /** Pigeon Status Frame */
  public enum PigeonStatusFrame {
    PITCH,
    YAW,
    ROLL,
    YAW_RATE
  }

  /**
   * Pigeon sensor inputs
   */
  @AutoLog
  public static class Pidgeon2Inputs {
    public Measure<Angle> pitchAngle = Units.Radians.of(0.0);
    public Measure<Angle> yawAngle = Units.Radians.of(0.0);
    public Measure<Angle> rollAngle = Units.Radians.of(0.0);
    public Measure<Velocity<Angle>> yawRate = Units.RadiansPerSecond.of(0.0);
    public Rotation2d rotation2d = GlobalConstants.ROTATION_ZERO;
  }

  private Pigeon2 m_pidgeon;

  private ID m_id;
  private Pidgeon2InputsAutoLogged m_inputs;
  private SimDouble m_simPidgeonYaw;

  private double yawOffset = 0;
  private static final int UPDATE_FREQUENCY = 100;

  public Pidgeon2(ID id) {
    this.m_id = id;
    this.m_pidgeon = new Pigeon2(id.deviceID, id.bus.name);
    this.m_inputs = new Pidgeon2InputsAutoLogged();
    this.m_simPidgeonYaw = new SimDouble(SimDeviceDataJNI.getSimValueHandle(SimDeviceDataJNI.getSimDeviceHandle("Pidgeon_Sensor[0]"), "Yaw"));
    setStatusFramePeriod(PigeonStatusFrame.YAW, UPDATE_FREQUENCY);
    setStatusFramePeriod(PigeonStatusFrame.YAW_RATE, UPDATE_FREQUENCY);
    m_pidgeon.optimizeBusUtilization();
    periodic();
  }

  /**
	 * Get the pitch from the Pigeon
	 * @return Pitch
	 */
  private double getPitch() {
    return m_pidgeon.getPitch().getValue();
  }

  /**
   * Returns the heading of the robot in degrees.
   * <p>
   * The angle increases as the Pigeon 2 turns clockwise when looked
   * at from the top. This follows the NED axis convention.
   * <p>
   * The angle is continuous; that is, it will continue from 360 to
   * 361 degrees. This allows for algorithms that wouldn't want to
   * see a discontinuity in the gyro output as it sweeps past from
   * 360 to 0 on the second time around.
   *
   * @return The current heading of the robot in degrees
   */
  private double getAngle() {
    return m_pidgeon.getAngle();
  }

  /**
   * Returns the heading of the robot as a {@link Rotation2d}.
   * <p>
   * The angle increases as the Pigeon 2 turns counterclockwise when
   * looked at from the top. This follows the NWU axis convention.
   * <p>
   * The angle is continuous; that is, it will continue from 360 to
   * 361 degrees. This allows for algorithms that wouldn't want to
   * see a discontinuity in the gyro output as it sweeps past from
   * 360 to 0 on the second time around.
   *
   * @return The current heading of the robot as a {@link Rotation2d}
   */
  private Rotation2d getRotation2d() {
    return m_pidgeon.getRotation2d();
  }

  /**
	 * Get the roll from the Pigeon
	 * @return Roll
	 */
  private double getRoll() {
    return m_pidgeon.getRoll().getValue();
  }

  /**
   * Return the rate of rotation of the yaw (Z-axis) gyro, in degrees per second.
   *<p>
   * The rate is based on the most recent reading of the yaw gyro angle.
   *<p>
   * @return The current rate of change in yaw angle (in degrees per second)
   */
  private double getRate() {
    return m_pidgeon.getRate();
  }


  /**
   * Update Pidgeon input readings
   */
  private void updateInputs() {
    //m_inputs.pitchAngle = Units.Degrees.of(getPitch());
    m_inputs.yawAngle = Units.Degrees.of(getAngle());
    //m_inputs.rollAngle = Units.Degrees.of(getRoll());
    m_inputs.yawRate = Units.DegreesPerSecond.of(getRate());
    m_inputs.rotation2d = getRotation2d();
  }

  /**
   * Call this method periodically
   */
  @Override
  public void periodic() {
    updateInputs();
    Logger.processInputs(m_id.name, m_inputs);
  }

  /**
   * Get latest sensor input data
   * @return Latest sensor data
   */
  @Override
  public Pidgeon2InputsAutoLogged getInputs() {
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
   * Configures all persistent settings to defaults (overloaded so timeoutMs is 50 ms).
   *
   * @return Status Code generated by function. 0 indicates no error.
   */
  public StatusCode configFactoryDefault() {
    return m_pidgeon.getConfigurator().apply(new Pigeon2Configuration());
  }

	/**
	 * Configure the Mount Pose using pitch, roll, and yaw.
	 *
	 * @param pitch The mounting calibration pitch-component
	 * @param roll The mounting calibration roll-component
   * @param yaw The mounting calibration yaw-component
	 * @return Status Code of the set command.
	 */
  public StatusCode configMountPose(double pitch, double roll, double yaw) {
    var toApply = new MountPoseConfigs();
    toApply.MountPosePitch = pitch;
    toApply.MountPoseRoll = roll;
    toApply.MountPoseYaw = yaw;
    return m_pidgeon.getConfigurator().apply(toApply);
  }

	/**
	 * Sets the period of the given status frame.
	 *
	 * @param statusFrame
	 *            Frame whose period is to be changed.
	 * @param frequencyHz
	 *            Frequency in Hz for the given frame.
	 * @return Status Code generated by function. 0 indicates no error.
	 */
  public StatusCode setStatusFramePeriod(PigeonStatusFrame statusFrame, int frequencyHz) {
    switch (statusFrame) {
      case PITCH:
        return m_pidgeon.getPitch().setUpdateFrequency(frequencyHz);
      case YAW:
        return m_pidgeon.getYaw().setUpdateFrequency(frequencyHz);
      case ROLL:
        return m_pidgeon.getRoll().setUpdateFrequency(frequencyHz);
      case YAW_RATE:
        return m_pidgeon.getAngularVelocityZWorld().setUpdateFrequency(frequencyHz);
      default:
        return StatusCode.OK;
    }
  }

  /**
   * Set yaw angle for simulator
   * @param angle Angle to set in degrees
   */
  public void setSimAngle(double angle) {
    m_simPidgeonYaw.set(angle);
  }

  /**
   * Get yaw angle for simulator
   * @return Simulated angle that was set
   */
  public double getSimAngle() {
    return m_simPidgeonYaw.get();
  }


  /**
   * Resets the Pigeon 2 to a heading of zero.
   * <p>
   * This can be used if there is significant drift in the gyro,
   * and it needs to be recalibrated after it has been running.
   */
  public void reset() {
    yawOffset = m_pidgeon.getYaw().getValueAsDouble();
    m_simPidgeonYaw.set(0.0);
  }

  @Override
  public void close() {
    m_pidgeon.close();
  }
}
