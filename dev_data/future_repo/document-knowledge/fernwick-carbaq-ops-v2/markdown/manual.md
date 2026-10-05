# Fernwick Labs CarbaQ CO2 Capture Solution Operations Manual

Source PDF: [original.pdf](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf)

<!-- page: 1 -->
<a id="page-1"></a>

---

Page source: [page 1](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=1)


CarbaQ CO2 Capture Solution
Operations Manual
Version 2.0
Fernwick Labs
This manual describes installation, operation, maintenance and troubleshooting of the CarbaQ unit, which recovers
CO2 from fermentation off-gas and stores it as a liquid in a receiver vessel.

<!-- page: 2 -->
<a id="page-2"></a>

---

Page source: [page 2](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=2)


Contents
1. Overview of the CarbaQ CO2 Capture Unit
2. Installation
3. Operating the Unit
4. Daily Checks
5. CarbaQ Software Components
6. Maintenance
7. Cleaning and Storage
8. Gas Quality Checks
9. Troubleshooting Tips
10. Service and Support

<!-- page: 3 -->
<a id="page-3"></a>

---

Page source: [page 3](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=3)


1. Overview of the CarbaQ CO2 Capture Unit
Fermentation off-gas enters the unit through the foam trap, passes the activated carbon bed that removes odours
and volatile organic compounds, and is compressed, cooled in the chiller and condensed into the receiver vessel.
An oxygen sensor monitors the purity of the gas before it is condensed.

<!-- page: 4 -->
<a id="page-4"></a>

---

Page source: [page 4](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=4)


1.1 Process flow
Foam trap, activated carbon bed, compressor, chiller, receiver vessel. The heater keeps the gas above its dew
point before the carbon bed.

<!-- page: 5 -->
<a id="page-5"></a>

---

Page source: [page 5](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=5)


1.2 Main components
The skid carries the compressor, the chiller, the carbon bed, the heater and the control cabinet with the HMI. The
receiver vessel stands next to the skid.

<!-- page: 6 -->
<a id="page-6"></a>

---

Page source: [page 6](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=6)


1.3 Safety
CO2 displaces oxygen. Operate the unit only in a ventilated area with a CO2 monitor. The receiver vessel is a
pressure vessel; never exceed its rated pressure.

<!-- page: 7 -->
<a id="page-7"></a>

---

Page source: [page 7](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=7)


1.4 Technical data
Capture rate, electrical supply and receiver capacity are given on the data plate of the unit.

<!-- page: 8 -->
<a id="page-8"></a>

---

Page source: [page 8](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=8)


2. Installation
Place the skid on a level floor near the fermentation vessels. Keep the inlet hose short and free of low points where
foam can collect.

<!-- page: 9 -->
<a id="page-9"></a>

---

Page source: [page 9](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=9)


2.1 Electrical connection
Connect the unit to the supply given on the data plate. The unit must be earthed.

<!-- page: 10 -->
<a id="page-10"></a>

---

Page source: [page 10](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=10)


2.2 Preparing the receiver vessel
Attach the receiver vessel to the liquid outlet of the chiller and open the receiver vent to purge air from the line.

<!-- page: 11 -->
<a id="page-11"></a>

---

Page source: [page 11](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=11)


2.3 Setting the back pressure regulator
The back pressure regulator (BPR) keeps the receiver at the pressure needed for liquefaction. Set the BPR so that
Receiver Pressure reads 140 psi with the unit running.
Turn the BPR adjusting screw in steps of about 1/8 of a turn and wait for the pressure to settle after each step.
Lock the adjusting screw when the value is reached.

<!-- page: 12 -->
<a id="page-12"></a>

---

Page source: [page 12](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=12)


2.4 Leak check
Check all connections with leak detection spray before the first start.

<!-- page: 13 -->
<a id="page-13"></a>

---

Page source: [page 13](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=13)


3. Operating the Unit
Start the unit from the HMI. The compressor starts when the oxygen content of the gas is below the set limit. Stop
the unit from the HMI at the end of the fermentation.

<!-- page: 14 -->
<a id="page-14"></a>

---

Page source: [page 14](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=14)


4. Daily Checks
Check the foam trap, the sight glass, the receiver pressure and the HMI alarm list every day.

<!-- page: 15 -->
<a id="page-15"></a>

---

Page source: [page 15](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=15)


5. CarbaQ Software Components
The HMI shows the process screen, the trend screen and the alarm screen. The alarm screen lists active alarms
with their time stamp.

<!-- page: 16 -->
<a id="page-16"></a>

---

Page source: [page 16](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=16)


5.1 HMI Alarms
The unit raises alarms for high pressure, high temperature, low oxygen purity and compressor faults. Shutdown
alarms stop the compressor and must be acknowledged before restart.

<!-- page: 17 -->
<a id="page-17"></a>

---

Page source: [page 17](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=17)


6. Maintenance
Follow the maintenance intervals below. Record every maintenance action in the service log.

<!-- page: 18 -->
<a id="page-18"></a>

---

Page source: [page 18](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=18)


6.1 Replacing Activated Carbon
Replace the activated carbon when the VOC alarm occurs or after the interval on the data plate. Stop the unit and
depressurize the carbon bed before opening it.

<!-- page: 19 -->
<a id="page-19"></a>

---

Page source: [page 19](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=19)


6.2 Filters and drains
Replace the coalescing filter element and check the auto-drain valve every three months.

<!-- page: 20 -->
<a id="page-20"></a>

---

Page source: [page 20](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=20)


6.3 Calibration of Oxygen Sensor
Calibrate the oxygen sensor monthly with ambient air as span gas. Moisture on the sensor causes drift; dry the
sample line before calibration.
The O2 Percentage value on the HMI must read the span value within tolerance after calibration.

<!-- page: 21 -->
<a id="page-21"></a>

---

Page source: [page 21](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=21)


7. Cleaning and Storage
Clean the foam trap and the inlet hose after every fermentation cycle.

<!-- page: 22 -->
<a id="page-22"></a>

---

Page source: [page 22](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=22)


7.1 Storage
For storage, depressurize the unit, drain the chiller and close all valves.

<!-- page: 23 -->
<a id="page-23"></a>

---

Page source: [page 23](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=23)


7.2 Seasonal shutdown
Before a seasonal shutdown replace the activated carbon and the filter elements.

<!-- page: 24 -->
<a id="page-24"></a>

---

Page source: [page 24](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=24)


8. Gas Quality Checks
Take a gas sample from the receiver and analyse oxygen, VOC and moisture. A foam trap that is not emptied
regularly raises the VOC content.

<!-- page: 25 -->
<a id="page-25"></a>

---

Page source: [page 25](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25)


9. Troubleshooting Tips

| Problem | Possible cause | Remedy |
| --- | --- | --- |
| Moisture in sight glass | Condensate carried over from the chiller | Drain the chiller separator and check the auto-drain valve. |
| Uneven gas flow; gas enters the unit in surges | Foam trap overfilled or a leak on the inlet hose | Empty the foam trap and tighten the inlet hose connections. |
| Chiller High Pressure Shutdown with moisture in the carbon bed | Moisture reached the activated carbon bed | Depressurize the unit, then dry or replace the carbon bed before restarting. |
| Receiver High Pressure Shutdown | Back pressure regulator set too high | Lower the BPR setting and vent the receiver slowly. |
| Chiller High Pressure Shutdown | Back pressure regulator was moved | Adjust the BPR about 1/8 of a turn at a time until Receiver Pressure reads 140 psi, then lock the adjusting screw. Allow up to 15 minutes for the pressure to settle after each step. |
| Compressor High Pressure Shutdown | Blockage downstream of the compressor | Check the auto-drain and the check valve for blockage. |
| Heater High Temperature Shutdown | Heater thermostat fault or no gas flow | Check gas flow and the heater thermostat. |

<!-- page: 26 -->
<a id="page-26"></a>

---

Page source: [page 26](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=26)


9. Troubleshooting Tips (continued)

| Problem | Possible cause | Remedy |
| --- | --- | --- |
| Negative temperature but no liquid in the receiver | Receiver insulation damaged or vent open | Check the receiver insulation and close the vent valve. |
| Over pressurization of the compressor | Coalescing filter blocked or outlet valve closed | Replace the coalescing filter; open the outlet valve. |
| Compressor false operation | Contactor, breaker or VFD fault | Reset the breaker, check the contactor and the VFD fault log on the HMI. |

<!-- page: 27 -->
<a id="page-27"></a>

---

Page source: [page 27](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=27)


10. Service and Support
For an urgent problem call the service hotline. For all other questions write to service@fernwick-labs.example and
quote the serial number of the unit.

<!-- page: 28 -->
<a id="page-28"></a>

---

Page source: [page 28](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=28)


Revision History
Version 2.0: troubleshooting table extended; BPR procedure updated.
