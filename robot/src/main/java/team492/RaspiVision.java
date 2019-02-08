package team492;

import com.google.gson.Gson;
import edu.wpi.first.networktables.EntryListenerFlags;
import edu.wpi.first.networktables.EntryNotification;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RaspiVision
{
    private static final boolean DEFAULT_USE_VISION_YAW = false;

    private Robot robot;
    private volatile RelativePose relativePose = null;
    private Gson gson;
    private AtomicInteger consecutiveTargetFrames = new AtomicInteger(0);
    private int maxAverageWindow = 10; // the last 10 frames
    private List<RelativePose> frames = new LinkedList<>();
    private final Object framesLock = new Object();
    private boolean useVisionYaw = DEFAULT_USE_VISION_YAW;

    public RaspiVision(Robot robot)
    {
        this.robot = robot;
        NetworkTable table = NetworkTableInstance.getDefault().getTable("RaspiVision");
        NetworkTableEntry entry = table.getEntry("VisionData");
        gson = new Gson();
        entry.addListener(this::updateTargetInfo,
            EntryListenerFlags.kNew | EntryListenerFlags.kUpdate | EntryListenerFlags.kImmediate);
    }

    /**
     * If useVisionYaw is enabled, the angle of the hatch/cargo panel is calculated using the vision code. If it is
     * disabled, the hatch/cargo panel angle will be calculated using the robot gyro. Theoretically, if the vision code
     * is robust enough it could have better performance than the gyro. In practice, the vision code is noisy, so using
     * the gyro might be a safer bet.
     *
     * @param enabled If true, enable useVisionYaw. If false, disable it.
     */
    public void setUseVisionYawEnabled(boolean enabled)
    {
        useVisionYaw = enabled;
    }

    private void updateTargetInfo(EntryNotification event)
    {
        String info = event.value.getString();
        if ("".equals(info))
        {
            // We have not found a pose, so set to null
            this.relativePose = null;
            synchronized (framesLock)
            {
                // Clear the frames list if it is not empty
                if (!frames.isEmpty())
                {
                    frames.clear();
                }
            }
            // Reset the number of consecutive frames
            consecutiveTargetFrames.set(0);
        }
        else
        {
            // Deserialize the latest calculated pose
            this.relativePose = gson.fromJson(info, RelativePose.class);
            // If configured to not use vision yaw, use yaw from gyro data.
            if (!useVisionYaw)
            {
                correctObjectYaw(relativePose);
            }
            // Increment the number of consecutive frames
            this.consecutiveTargetFrames.incrementAndGet();
            synchronized (framesLock)
            {
                // Add the latest pose
                frames.add(relativePose);
                // Trim the list so only the last few are kept
                while (frames.size() > maxAverageWindow)
                {
                    frames.remove(0);
                }
            }
        }
    }

    private int round(double d)
    {
        return (int) Math.floor(d + 0.5);
    }

    /**
     * Correct the object yaw value using gyro data, since the required heading is some multiple of 45.
     *
     * @param pose The pose object to correct
     */
    private void correctObjectYaw(RelativePose pose)
    {
        double robotHeading = robot.driveBase.getHeading();
        int multiple = round(robotHeading / 45.0);
        double requiredHeading = multiple * 45.0;
        pose.objectYaw =  requiredHeading - robotHeading;
    }

    /**
     * Get the average pose of the last n frames where n=maxAverageWindow.
     *
     * @return The average pose. (all attributes averaged)
     */
    public RelativePose getAveragePose()
    {
        return getAveragePose(maxAverageWindow);
    }

    /**
     * Calculates the average pose of the last numFrames frames.
     *
     * @param numFrames How many frames to average.
     * @return The average pose.
     */
    public RelativePose getAveragePose(int numFrames)
    {
        int fromIndex = Math.max(0, frames.size() - numFrames);
        RelativePose average = new RelativePose();
        List<RelativePose> poses = frames.subList(fromIndex, frames.size() - 1);
        for (RelativePose pose : poses)
        {
            average.objectYaw += pose.objectYaw;
            average.r += pose.r;
            average.theta += pose.theta;
            average.x += pose.x;
            average.y += pose.y;
        }
        average.objectYaw /= poses.size();
        average.r /= poses.size();
        average.theta /= poses.size();
        average.x /= poses.size();
        average.y /= poses.size();
        return average;
    }

    /**
     * Set the max number of frames to keep. You will not be able to average more frames than this.
     *
     * @param numFrames How many frames to keep for averaging at maximum.
     */
    public void setMaxAverageWindow(int numFrames)
    {
        if (numFrames < 0)
        {
            throw new IllegalArgumentException("numFrames must be >= 0!");
        }
        this.maxAverageWindow = numFrames;
    }

    /**
     * Gets how many consecutive frames the target has been detected.
     *
     * @return The number of consecutive frames the target has been detected.
     */
    public int getConsecutiveTargetFrames()
    {
        return consecutiveTargetFrames.get();
    }

    /**
     * Gets the last calculated pose.
     *
     * @return The pose calculated by the vision system. If no object detected, returns null.
     */
    public RelativePose getLastPose()
    {
        return relativePose;
    }

    public static class RelativePose
    {
        public double r, theta, objectYaw, x, y;
    }
}
