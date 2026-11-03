package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Architecture.archAsString;

import android.app.Activity;
import android.content.res.AssetManager;
import android.util.Log;

import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.utils.MathUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class NewJREUtil {
    private static boolean checkInternalRuntime(AssetManager assetManager, InternalRuntime internalRuntime) {
        String launcher_runtime_version;
        String installed_runtime_version = MultiRTUtils.readInternalRuntimeVersion(internalRuntime.name);
        try {
            launcher_runtime_version = Tools.read(assetManager.open(internalRuntime.path+"/version"));
        }catch (IOException exc) {
            //we don't have a runtime included!
            //if we have one installed -> return true -> proceed (no updates but the current one should be functional)
            //if we don't -> return false -> Cannot find compatible Java runtime
            return installed_runtime_version != null;
        }
        // this implicitly checks for null, so it will unpack the runtime even if we don't have one installed
        if(!launcher_runtime_version.equals(installed_runtime_version))
            return unpackInternalRuntime(assetManager, internalRuntime, launcher_runtime_version);
        else return true;
    }

    private static java.io.InputStream openArchitectureBin(AssetManager assetManager, String basePath) throws IOException {
        String arch = archAsString(Tools.DEVICE_ARCHITECTURE);
        try {
            return assetManager.open(basePath + "/bin-" + arch + ".tar.xz");
        } catch (IOException e) {
            String[] fallbacks = new String[]{"arm64", "arm", "x86_64", "x86"};
            for (String fallback : fallbacks) {
                try {
                    return assetManager.open(basePath + "/bin-" + fallback + ".tar.xz");
                } catch (IOException ignored) {}
            }
            throw e;
        }
    }

    private static boolean unpackInternalRuntime(AssetManager assetManager, InternalRuntime internalRuntime, String version) {
        try {
            MultiRTUtils.installRuntimeNamedBinpack(
                    assetManager.open(internalRuntime.path+"/universal.tar.xz"),
                    openArchitectureBin(assetManager, internalRuntime.path),
                    internalRuntime.name, version);
            MultiRTUtils.postPrepare(internalRuntime.name);
            return true;
        }catch (Exception e) {
            Log.e("NewJREAuto", "Internal JRE unpack failed", e);
            java.io.File javaBin = new java.io.File(Tools.MULTIRT_HOME, internalRuntime.name + "/bin/java");
            java.io.File release = new java.io.File(Tools.MULTIRT_HOME, internalRuntime.name + "/release");
            if (javaBin.exists() && release.exists()) {
                Log.i("NewJREAuto", "Java binary and release exist, proceeding!");
                return true;
            }
            return false;
        }
    }

    private static InternalRuntime getInternalRuntime(Runtime runtime) {
        for(InternalRuntime internalRuntime : InternalRuntime.values()) {
            if(internalRuntime.name.equals(runtime.name)) return internalRuntime;
        }
        return null;
    }

    private static MathUtils.RankedValue<Runtime> getNearestInstalledRuntime(int targetVersion) {
        List<Runtime> runtimes = MultiRTUtils.getRuntimes();
        return MathUtils.findNearestPositive(targetVersion, runtimes, (runtime)->runtime.javaVersion);
    }

    private static MathUtils.RankedValue<InternalRuntime> getNearestInternalRuntime(int targetVersion) {
        List<InternalRuntime> runtimeList = Arrays.asList(InternalRuntime.values());
        return MathUtils.findNearestPositive(targetVersion, runtimeList, (runtime)->runtime.majorVersion);
    }


    /** @return true if everything is good, false otherwise.  */
    public static boolean installNewJreIfNeeded(Activity activity, JMinecraftVersionList.Version versionInfo) {
        //Now we have the reliable information to check if our runtime settings are good enough
        if (versionInfo.javaVersion == null || versionInfo.javaVersion.component.equalsIgnoreCase("jre-legacy"))
            return true;

        int gameRequiredVersion = versionInfo.javaVersion.majorVersion;

        LauncherProfiles.load();
        AssetManager assetManager = activity.getAssets();
        MinecraftProfile minecraftProfile = LauncherProfiles.getCurrentProfile();
        String profileRuntime = Tools.getSelectedRuntime(minecraftProfile);
        Runtime runtime = MultiRTUtils.read(profileRuntime);
        // Partly trust the user with his own selection, if the game can even try to run in this case
        if (runtime.javaVersion >= gameRequiredVersion || (runtime.javaVersion > 0 && gameRequiredVersion > 21)) {
            // Check whether the selection is an internal runtime
            InternalRuntime internalRuntime = getInternalRuntime(runtime);
            // If it is, check if updates are available from the APK file
            if(internalRuntime != null) {
                // Not calling showRuntimeFail on failure here because we did, technically, find the compatible runtime
                return checkInternalRuntime(assetManager, internalRuntime);
            }
            return true;
        }

        // If the runtime version selected by the user is not appropriate for this version (which means the game won't run at all)
        // automatically pick from either an already installed runtime, or a runtime packed with the launcher
        MathUtils.RankedValue<?> nearestInstalledRuntime = getNearestInstalledRuntime(gameRequiredVersion);
        MathUtils.RankedValue<?> nearestInternalRuntime = getNearestInternalRuntime(gameRequiredVersion);

        MathUtils.RankedValue<?> selectedRankedRuntime = MathUtils.objectMin(
                nearestInternalRuntime, nearestInstalledRuntime, (value)->value.rank
        );

        Object selected;
        String appropriateRuntime;
        InternalRuntime internalRuntime;

        if (selectedRankedRuntime != null) {
            selected = selectedRankedRuntime.value;
        } else {
            // Target version exceeded available runtimes (e.g. Java 25 requested).
            // Fall back to highest available internal runtime (JRE 21) instead of blocking.
            Log.w("NewJREAuto", "Target version " + gameRequiredVersion + " exceeded available runtimes, falling back to JRE 21");
            selected = InternalRuntime.JRE_21;
        }

        // Perform checks on the picked runtime
        if(selected instanceof Runtime) {
            // If it's an already installed runtime, save its name and check if
            // it's actually an internal one (just in case)
            Runtime selectedRuntime = (Runtime) selected;
            appropriateRuntime = selectedRuntime.name;
            internalRuntime = getInternalRuntime(selectedRuntime);
        } else if (selected instanceof InternalRuntime) {
            // If it's an internal runtime, set it's name as the appropriate one.
            internalRuntime = (InternalRuntime) selected;
            appropriateRuntime = internalRuntime.name;
        } else {
            throw new RuntimeException("Unexpected type of selected: "+selected.getClass().getName());
        }

        // If it turns out the selected runtime is actually an internal one, attempt automatic installation or update
        if(internalRuntime != null && !checkInternalRuntime(assetManager, internalRuntime)) {
            // Not calling showRuntimeFail here because we did, technically, find the compatible runtime
            return false;
        }

        minecraftProfile.javaDir = Tools.LAUNCHERPROFILES_RTPREFIX + appropriateRuntime;
        LauncherProfiles.write();
        return true;
    }

    private static void showRuntimeFail(Activity activity, JMinecraftVersionList.Version verInfo) {
        Tools.dialogOnUiThread(activity, activity.getString(R.string.global_error),
                activity.getString(R.string.multirt_nocompatiblert, verInfo.javaVersion.majorVersion));
    }

    private enum InternalRuntime {
        JRE_17(17, "Internal-17", "components/jre-new"),
        JRE_21(21, "Internal-21", "components/jre-21");
        public final int majorVersion;
        public final String name;
        public final String path;
        InternalRuntime(int majorVersion, String name, String path) {
            this.majorVersion = majorVersion;
            this.name = name;
            this.path = path;
        }
    }

}