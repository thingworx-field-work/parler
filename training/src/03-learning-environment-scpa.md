# Learning environment (ThingWorx + SCPA)

## Goal

Stand up a **known-good ThingWorx server** and load the **SCPA utilization** scenario so later chapters always hit the same **Things**, **Mashups**, and **Networks**.

## Steps

### Setup ThingWorx SCPA environment

Please go to PTC cloud and select the following template to setup an instance.

<img src="./__images__//image-20260607220946733.png" alt="image-20260607220946733" style="zoom:50%;" />

Once the instance is up, please visit SCPA landing page from the PTC cloud URL.

<img src="./__images__//image-20260530001506078.png" alt="image-20260530001506078" style="zoom:50%;" />

Please click the `SCPA Main Mashup` (`PTCTS.AssetMonitoring.Main_MU`), and then click the `Monitor` button and check the `Asset Type` and `Regions` area. You may have to refresh the page several times. Once you can see the asset type and region look at the below page, it means your ThingWorx SCPA instance is ready.

<img src="./__images__//image-20260530002637279.png" alt="image-20260530002637279" style="zoom:50%;" />

### Check SCPA Demo Data

#### Alert Summary and Alert History

The first step is to check `Alert Summary` and `Alert History`. Please go to ThingWorx composer and click the `monitor` button on the left menu bar, and then click `Alert Summary` and `Alert History`, as long as there are live demo data, it's a workable system for alert analysis.

<img src="./__images__//image-20260530004457330.png" alt="image-20260530004457330" style="zoom:33%;" />

The data on the UI may be different, but there have to be some data.

<img src="./__images__//image-20260530004538566.png" alt="image-20260530004538566" style="zoom:33%;" />

#### Utilization Data

Please click the `Browse` button on the left menu bar and click `Streams` and make sure there is a stream with name: `PTCSC.UtilizationTWImpl.Utilization_SM`

<img src="./__images__//image-20260601235808355.png" alt="image-20260601235808355" style="zoom:50%;" />

You can click the `Mashup` tab on the stream page, you can see the live utilization data.

<img src="./__images__//image-20260601235849541.png" alt="image-20260601235849541" style="zoom:50%;" />



#### Setup a project for the whole training

Let's create a project with name: `Parler_SCPA_Guidance`, and we will use it all the way to manage entities created in this journey.

<img src="./__images__//image-20260531140254041.png" alt="image-20260531140254041" style="zoom:50%;" />

#### Setup two repositories

In ThingWorx, please create two repositories for future usage:

* Configuration Repository: It will be used to store all configuration files for a specific `AgentThing`. In this training material, I will use name: `ConfigurationRepository`
* Export Repository: It will be used to store the truth of data retrieved from ThingWorx for user to download and validate. In this training material, I will use name: `AIDocRepository`

You can create a new Repository by creating a new Thing with `FileRepository` template, and make sure to set the `Project` name property to `Parler_SCPA_Guidance`.
<img src="./__images__//image-20260603152650542.png" alt="image-20260603152650542" style="zoom:80%;" />

You should see this once you're done:

<img src="./__images__//image-20260530015121841.png" alt="image-20260530015121841" style="zoom:50%;" />
