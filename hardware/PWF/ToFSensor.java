// Copyright (c) LASA Robotics and other contributors
// Open Source Software; you can modify and/or share it under the terms of
// the MIT license file in the root directory of this project.

package org.lasarobotics.hardware.PWF;

import org.lasarobotics.hardware.LoggableHardware;
import org.littletonrobotics.junction.AutoLog;
import org.littletonrobotics.junction.Logger;

import com.playingwithfusion.TimeOfFlight;
import com.playingwithfusion.TimeOfFlight.RangingMode;
import com.playingwithfusion.TimeOfFlight.Status;

import frc.team5406.robot.Constants;


/** CTRE CANCoder */
public class ToFSensor implements LoggableHardware, AutoCloseable {
  /** CANCoder Status Frame */
  

  /** CANCoder ID */
  public static class ID {
    public final String name;
    public final int deviceID;

    /**
     * CANCoder ID
     * @param name Device name for logging
     * @param deviceID CAN ID
     */
    public ID(String name, int deviceID) {
      this.name = name;
      this.deviceID = deviceID;
    }
  }

  @AutoLog
  public static class ToFSensorInputs {
    public double distance = 0.0;
    public Status status = Status.Invalid;
    public boolean valid = false;
  }

  private com.playingwithfusion.TimeOfFlight m_ToFSensor;

  private ID m_id;
  private ToFSensorInputsAutoLogged m_inputs;

  public ToFSensor(ID id) {
    this.m_id = id;
    this.m_ToFSensor = new com.playingwithfusion.TimeOfFlight(m_id.deviceID);
    this.m_inputs = new ToFSensorInputsAutoLogged();
  }
//3 methods:
public double getToFDistance() {
    return getToFDistance(0);
  }
  public double getToFDistance(double offset) {
    return m_ToFSensor.getRange()+ offset;
  }


  public Status getToFStatus() {
    return m_ToFSensor.getStatus();
  }
  public boolean isValid() {
    return m_ToFSensor.getStatus()==Status.Valid;
  }
public void setRangingMode(RangingMode mode, double sampleTime) {
       m_ToFSensor.setRangingMode(mode, sampleTime);
}

  private void updateInputs() {
    m_inputs.distance = getToFDistance();
    m_inputs.status = getToFStatus();
    m_inputs.valid = isValid();
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
  public ToFSensorInputsAutoLogged getInputs() {
    return m_inputs;
  }

  /**
   * Get device ID
   * @return Device ID
   */
  public ID getID() {
    return m_id;
  }

   @Override
  public void close() {
    m_ToFSensor = null;
  }

}