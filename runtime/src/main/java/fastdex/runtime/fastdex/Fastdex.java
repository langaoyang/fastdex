package fastdex.runtime.fastdex;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import dalvik.system.PathClassLoader;
import fastdex.common.ShareConstants;
import fastdex.common.utils.FileUtils;
import fastdex.runtime.Constants;
import fastdex.runtime.FastdexApplication;
import fastdex.runtime.FastdexRuntimeException;
import fastdex.runtime.fd.Server;
import fastdex.runtime.loader.SystemClassLoaderAdder;
import fastdex.runtime.loader.ResourcePatcher;
import fastdex.runtime.loader.shareutil.SharePatchFileUtil;

/**
 * Created by tong on 17/4/29.
 */
public class Fastdex {
    public static final String LOG_TAG = Fastdex.class.getSimpleName();

    private static Fastdex instance;

    final RuntimeMetaInfo runtimeMetaInfo;
    final File fastdexDirectory;
    final File patchDirectory;
    final File tempDirectory;
//    final File dexDirectory;
//    final File resourceDirectory;
    private boolean fastdexEnabled = true;

    public static Fastdex get(Context context) {
        if (instance == null) {
            synchronized (Fastdex.class) {
                if (instance == null) {
                    instance = new Fastdex(context);
                }
            }
        }
        return instance;
    }

    private Context applicationContext;

    public Fastdex(Context applicationContext) {
        this.applicationContext = applicationContext;

        fastdexDirectory = SharePatchFileUtil.getFastdexDirectory(applicationContext);
        patchDirectory = SharePatchFileUtil.getPatchDirectory(applicationContext);
        tempDirectory = SharePatchFileUtil.getPatchTempDirectory(applicationContext);
//        dexDirectory = new File(fastdexDirectory,Constants.DEX_DIR);
//        resourceDirectory = new File(fastdexDirectory,Constants.RES_DIR);

        RuntimeMetaInfo metaInfo = RuntimeMetaInfo.load(this);
        RuntimeMetaInfo assetsMetaInfo = null;

        long lastSourceModified = SharePatchFileUtil.getLastSourceModified(applicationContext);
        Log.d(Fastdex.LOG_TAG,"lastSourceModified: " + lastSourceModified);
        try {
            InputStream is = applicationContext.getAssets().open(ShareConstants.META_INFO_FILENAME);
            String assetsMetaInfoJson = new String(FileUtils.readStream(is));
            Log.d(Fastdex.LOG_TAG,"load meta-info from assets: \n" + assetsMetaInfoJson);
            assetsMetaInfo = RuntimeMetaInfo.load(assetsMetaInfoJson);
            if (assetsMetaInfo == null) {
                throw new NullPointerException("AssetsMetaInfo can not be null!!!");
            }

            boolean metaInfoChanged = false;
            boolean sourceChanged = false;

            if (metaInfo == null) {
                File metaInfoFile = new File(fastdexDirectory, ShareConstants.META_INFO_FILENAME);
                FileUtils.cleanDir(patchDirectory);

                assetsMetaInfo.setLastSourceModified(lastSourceModified);
                assetsMetaInfo.save(this);
                metaInfo = assetsMetaInfo;

                if (!FileUtils.isLegalFile(metaInfoFile)) {
                    throw new FastdexRuntimeException("save meta-info fail: " + metaInfoFile.getAbsolutePath());
                }
            }
            else if (!(metaInfoChanged = metaInfo.equals(assetsMetaInfo)) || (sourceChanged = metaInfo.getLastSourceModified() != lastSourceModified)) {
                File metaInfoFile = new File(fastdexDirectory, ShareConstants.META_INFO_FILENAME);
                String metaInfoJson = new String(FileUtils.readContents(metaInfoFile));

                try {
                    JSONObject jObj = new JSONObject(metaInfoJson);
                    jObj.put("sourceChanged",sourceChanged);
                    metaInfoJson = jObj.toString();
                } catch (Throwable e) {

                }

                Log.d(Fastdex.LOG_TAG,"load meta-info from files: \n" + metaInfoJson);

                if (metaInfoChanged && sourceChanged) {
                    Log.d(Fastdex.LOG_TAG,"\nmeta-info content and source changed, clean patch info\n");
                }
                else if (metaInfoChanged) {
                    Log.d(Fastdex.LOG_TAG,"\nmeta-info content changed, clean patch info\n");
                }
                else if (sourceChanged) {
                    Log.d(Fastdex.LOG_TAG,"\nmeta-info source changed, clean patch info\n");
                }

                FileUtils.cleanDir(patchDirectory);
                FileUtils.cleanDir(tempDirectory);

                assetsMetaInfo.setLastSourceModified(lastSourceModified);
                assetsMetaInfo.save(this);
                metaInfo = assetsMetaInfo;
            }
        } catch (Throwable e) {
            e.printStackTrace();
            fastdexEnabled = false;
            Log.d(LOG_TAG,"fastdex disabled: " + e.getMessage());
        }

        this.runtimeMetaInfo = metaInfo;
    }

    public Context getApplicationContext() {
        return applicationContext;
    }

    public void onAttachBaseContext(FastdexApplication fastdexApplication) {
        if (!fastdexEnabled) {
            return;
        }
        if (!TextUtils.isEmpty(runtimeMetaInfo.getPreparedPatchPath())) {
            if (!TextUtils.isEmpty(runtimeMetaInfo.getLastPatchPath())) {
                FileUtils.deleteDir(new File(runtimeMetaInfo.getLastPatchPath()));
            }
            File preparedPatchDir = new File(runtimeMetaInfo.getPreparedPatchPath());
            File patchDir = patchDirectory;

            FileUtils.deleteDir(patchDir);
            preparedPatchDir.renameTo(patchDir);

            trimPreparedPatchDir(runtimeMetaInfo,patchDir);

            runtimeMetaInfo.setLastPatchPath(runtimeMetaInfo.getPatchPath());
            runtimeMetaInfo.setPreparedPatchPath(null);
            runtimeMetaInfo.setPatchPath(patchDir.getAbsolutePath());
            runtimeMetaInfo.save(this);

            Log.d(LOG_TAG,"apply new patch: " + runtimeMetaInfo.toJson());
        }

        if (TextUtils.isEmpty(runtimeMetaInfo.getPatchPath())) {
            return;
        }

        final File dexDirectory = new File(new File(runtimeMetaInfo.getPatchPath()),Constants.DEX_DIR);
        final File optDirectory = new File(new File(runtimeMetaInfo.getPatchPath()),Constants.OPT_DIR);
        final File resourceDirectory = new File(new File(runtimeMetaInfo.getPatchPath()),Constants.RES_DIR);
        FileUtils.ensumeDir(optDirectory);
        File resourceApkFile = new File(resourceDirectory,Constants.RESOURCE_APK_FILE_NAME);
        if (FileUtils.isLegalFile(resourceApkFile)) {
            Log.d(LOG_TAG,"apply res patch: " + resourceApkFile);
            try {
                ResourcePatcher.monkeyPatchExistingResources(applicationContext,resourceApkFile.getAbsolutePath());
            } catch (Throwable throwable) {
                throw new FastdexRuntimeException(throwable);
            }
        }

        File mergedPatchDex = new File(dexDirectory,ShareConstants.MERGED_PATCH_DEX);
        File patchDex = new File(dexDirectory,ShareConstants.PATCH_DEX);

        ArrayList<File> dexList = new ArrayList<>();
        if (FileUtils.isLegalFile(patchDex)) {
            dexList.add(patchDex);
        }
        if (FileUtils.isLegalFile(mergedPatchDex)) {
            dexList.add(mergedPatchDex);
        }

        if (!dexList.isEmpty()) {
            PathClassLoader classLoader = (PathClassLoader) Fastdex.class.getClassLoader();
            try {
                Log.d(LOG_TAG,"apply dex patch: " + dexList);
                SystemClassLoaderAdder.installDexes(fastdexApplication,classLoader,optDirectory,dexList);
            } catch (Throwable throwable) {
                throw new FastdexRuntimeException(throwable);
            }
        }

        Server.showToast("fastdex, apply patch successful",applicationContext);
    }

    private void trimPreparedPatchDir(RuntimeMetaInfo runtimeMetaInfo, File patchDir) {
        //把文件名中的资源版本号提取出来
        final File dexDirectory = new File(patchDir,Constants.DEX_DIR);
        final File resourceDirectory = new File(patchDir,Constants.RES_DIR);

        File[] resFiles = resourceDirectory.listFiles();
        if (resFiles != null) {
            for (File f : resFiles) {
                if (Server.isResourcePath(f.getName())) {
                    String[] infoArr = f.getName().split(ShareConstants.RES_SPLIT_STR);

                    String version = infoArr[0];
                    String path = infoArr[1];

                    File target = new File(f.getParentFile(),path);
                    f.renameTo(target);

                    Log.d(LOG_TAG,"file: " + f + " renameTo: " + target);
                    if (path.equals(Constants.RESOURCE_APK_FILE_NAME)) {
                        runtimeMetaInfo.setResourcesVersion(Integer.parseInt(version));
                    }
                    break;
                }
            }
        }

        File[] dexFiles = dexDirectory.listFiles();
        if (dexFiles != null) {
            for (File f : dexFiles) {
                if (Server.isDexPath(f.getName())) {
                    String[] infoArr = f.getName().split(ShareConstants.RES_SPLIT_STR);

                    String version = infoArr[0];
                    String path = infoArr[1];

                    File target = new File(f.getParentFile(),path);
                    f.renameTo(target);

                    Log.d(LOG_TAG,"file: " + f + " renameTo: " + target);
                    if (path.equals(Constants.MERGED_PATCH_DEX)) {
                        runtimeMetaInfo.setMergedDexVersion(Integer.parseInt(version));
                    }
                    else if (path.equals(Constants.PATCH_DEX)) {
                        runtimeMetaInfo.setPatchDexVersion(Integer.parseInt(version));
                    }
                    break;
                }
            }
        }
    }

    public File getFastdexDirectory() {
        return fastdexDirectory;
    }

    public File getTempDirectory() {
        return tempDirectory;
    }

    public File getPatchDirectory() {
        return patchDirectory;
    }

    public RuntimeMetaInfo getRuntimeMetaInfo() {
        return runtimeMetaInfo;
    }

    public boolean isFastdexEnabled() {
        return fastdexEnabled;
    }
}
