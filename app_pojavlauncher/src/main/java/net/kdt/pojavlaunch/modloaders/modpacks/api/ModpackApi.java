package net.kdt.pojavlaunch.modloaders.modpacks.api;


import android.content.Context;
import android.widget.Toast;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;

/**
 *
 */
public interface ModpackApi {

    /**
     * @param searchFilters Filters
     * @param previousPageResult The result from the previous page
     * @return the list of mod items from specified offset
     */
    SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult);

    /**
     * @param searchFilters Filters
     * @return A list of mod items
     */
    default SearchResult searchMod(SearchFilters searchFilters) {
        return searchMod(searchFilters, null);
    }

    /**
     * Fetch the mod details
     * @param item The moditem that was selected
     * @return Detailed data about a mod(pack)
     */
    ModDetail getModDetails(ModItem item);

    /**
     * Download and install the mod(pack)
     * @param modDetail The mod detail data
     * @param selectedVersion The selected version
     */
    default void handleInstallation(Context context, ModDetail modDetail, int selectedVersion) {
        if(!modDetail.isModpack) {
            installSingleMod(context, modDetail, selectedVersion);
            return;
        }
        // Doing this here since when starting installation, the progress does not start immediately
        // which may lead to two concurrent installations (very bad)
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                ModLoader loaderInfo = installMod(modDetail, selectedVersion);
                if (loaderInfo == null) return;
                loaderInfo.getDownloadTask(new NotificationDownloadListener(context, loaderInfo)).run();
            }catch (IOException e) {
                Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
            }
        });
    }

    /**
     * Download a single mod/addon jar into the "mods" folder of the currently selected profile.
     * The mod must match the mod loader (Fabric/Forge/...) and Minecraft version of that profile.
     */
    static void installSingleMod(Context context, ModDetail modDetail, int selectedVersion) {
        String url = modDetail.versionUrls[selectedVersion];
        if(url == null || url.isEmpty()) {
            Tools.runOnUiThread(() -> Toast.makeText(context, R.string.mod_download_no_url, Toast.LENGTH_LONG).show());
            return;
        }
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File modsDir = new File(getCurrentProfileDir(), "mods");
                if(!modsDir.isDirectory() && !modsDir.mkdirs())
                    throw new IOException("Failed to create " + modsDir);
                ModDownloader downloader = new ModDownloader(modsDir, true);
                downloader.submitDownload(0, FileUtils.getFileName(url), modDetail.versionHashes[selectedVersion], url);
                downloader.awaitFinish((curr, max) -> ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK,
                        max > 0 ? (int) Math.min(100L, curr * 100L / max) : 0,
                        R.string.mod_download_downloading, modDetail.title));
                Tools.runOnUiThread(() -> Toast.makeText(context,
                        context.getString(R.string.mod_download_done, modDetail.title), Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                Tools.showErrorRemote(context, R.string.mod_download_failed, e);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            }
        });
    }

    /** @return the game directory of the currently selected launcher profile */
    static File getCurrentProfileDir() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profile == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profile);
    }

    /**
     * Install the mod(pack).
     * May require the download of additional files.
     * May requires launching the installation of a modloader
     * @param modDetail The mod detail data
     * @param selectedVersion The selected version
     */
    ModLoader installMod(ModDetail modDetail, int selectedVersion) throws IOException;
}
