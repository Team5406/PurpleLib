
# PurpleLib [![Release](https://jitpack.io/v/lasarobotics/PurpleLib.svg)](https://jitpack.io/#lasarobotics/PurpleLib)


Custom library for 418 Purple Haze

Note: CTRE will not be as well supported as REV products as our team primarily lives in the REV Robotics ecosystem

## Features
* Hardware wrappers with built-in AdvantageKit logging
  * REV Robotics
    * Spark Max with SmoothMotion<sup>TM</sup>
    * Spark Flex with SmoothMotion<sup>TM</sup>
    * Through bore encoder connected to Spark Max/Flex in absolute mode ONLY!
    * Spark Flex and NEO Vortex MUST be paired together!
    * 3-way communication to ensure parameters are set
    * Improved velocity PID performance
    * More accurate velocity readings
  * CTRE
    * CANivore
    * Pidgeon 2.0
    * CANCoder
    * VictorSPX
    * TalonSRX
  * Kauai Labs
    * NavX2 (MXP port only)
  * Generic
    * Analog sensor
    * Compressor
    * Single and double solenoid
    * Limit switch
    * Servo
* MAXSwerve module support
  * Supports NEO v1.0/1.1 or NEO Vortex + NEO 550 configuration only
  * REV through bore encoder must be used
  * Module must be calibrated using [REV MAXSwerve calibration tool](https://docs.revrobotics.com/sparkmax/software-resources/calibration-for-maxswerve)
* Robot rotation PID
* Traction control
* Swerve second order kinematics correction
* Configurable input maps
* LED strip support
* JSON read/write
* Battery scanning and tracking


## Installing
Add the following dependencies to your project:
* AdvantageKit - https://github.com/Mechanical-Advantage/AdvantageKit/blob/main/docs/INSTALLATION.md
* NavX - https://dev.studica.com/releases/2024/NavX.json
* REVLib - https://software-metadata.revrobotics.com/REVLib-2024.json
* CTRE Phoenix5 - https://maven.ctr-electronics.com/release/com/ctre/phoenix/Phoenix5-frc2024-latest.json
* CTRE Phoenix6 - https://maven.ctr-electronics.com/release/com/ctre/phoenix6/latest/Phoenix6-frc2024-latest.json

Add the following to `build.gradle` where VERSION is the release version, e.g. 2023.0.0
```
repositories {
  maven { url "https://jitpack.io" }
}
dependencies {
  implementation 'org.apache.commons:commons-math3:3.+'
  implementation 'com.github.lasarobotics:PurpleLib:VERSION'
}
```

## Releasing
Create a release in GitHub. JitPack does the rest.

## Examples
Usage examples can be found [here](https://github.com/lasarobotics/PurpleLibExamples)

An example swerve project is [here](https://github.com/lasarobotics/PurpleSwerve)

## Documentation
Javadocs available [here](https://jitpack.io/com/github/lasarobotics/PurpleLib/latest/javadoc/)

## Updating Fork
Instructions for updating our fork of PurpleLib and submodules:

* Sync fork on github

* git bash in a folder of your choice
```
git clone https://github.com/Team5406/PurpleLib.git
```
* If you want to list your remotes: `git remote -v`
  
* pull down changes:
```
git subtree pull --prefix src/main/java/org/lasarobotics origin src-main --squash
```
* check for any changes and merge conflcts with `git status`

* open each file that has merge conflicts inside vscode and deal with them

* stage all changes to files with `git add .`
```
git status
```
* if all modified files are green, proceed
```
git commit -m "resolved merge conflicts"
```
* confirm you got everything with `git subtree pull --prefix src/main/java/org/lasarobotics origin src-main --squash`

* push it up with `git subtree push --prefix src/main/java/org/lasarobotics origin src-main`

* do the same for src-test:
```
git subtree pull --prefix src/test/java/org/lasarobotics origin src-test --squash
git subtree push --prefix src/test/java/org/lasarobotics origin src-test
```

* double check again:
```
git checkout src-main
git pull
git checkout src-test
git pull
```
* go back to main and find a file with recent updates and then compare it (cat will open the file to view tis contents) to see that everything is up to date and nothing is missing:
```
git checkout src-main
cd ______
cat ______
```
check one more time with `git status`
