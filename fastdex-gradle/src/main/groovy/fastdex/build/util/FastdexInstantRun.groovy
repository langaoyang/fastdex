package fastdex.build.util

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import fastdex.build.variant.FastdexVariant

/**
 * Created by tong on 17/3/12.
 */
public class FastdexInstantRun {
    FastdexVariant fastdexVariant
    boolean manifestChanged
    boolean resourceChanged
    boolean sourceChanged

    boolean assetsChanged
    //boolean mergeFlavor1DebugAssets

    IDevice device

    boolean isInstallApk = true

    FastdexInstantRun(FastdexVariant fastdexVariant) {
        this.fastdexVariant = fastdexVariant
    }

    private void waitForDevice(AndroidDebugBridge bridge) {
        int count = 0;
        while (!bridge.hasInitialDeviceList()) {
            try {
                Thread.sleep(100);
                count++;
            } catch (InterruptedException ignored) {
            }
            if (count > 300) {
                throw new FastdexRuntimeException("Connect adb timeout!!")
            }
        }
    }

    def preparedDevice() {
        preparedDevice(false)
    }

    def preparedDevice(boolean background) {
        if (device != null) {
            return
        }
        AndroidDebugBridge.initIfNeeded(false)
        AndroidDebugBridge bridge =
                AndroidDebugBridge.createBridge(FastdexUtils.getAdbCmdPath(fastdexVariant.project), false)
        waitForDevice(bridge)
        IDevice[] devices = bridge.getDevices()
        if (devices != null && devices.length > 0) {
            if (devices.length > 1) {
                String errmsg = "发现了多个Android设备，请拔掉数据线，只留一个设备 V_V "
                if (background) {
                    fastdexVariant.project.logger(errmsg)
                }
                else {
                    throw new FastdexRuntimeException(errmsg)
                }
            }
            device = devices[0]
        }

        if (device == null) {
            String errmsg = "没有发现Android设备，请确认连接是否正常 adb devices"
            if (background) {
                fastdexVariant.project.logger(errmsg)
            }
            else {
                throw new FastdexRuntimeException(errmsg)
            }
        }
        fastdexVariant.project.logger.error("==fastdex device connected ${device.toString()}")
    }

    public void onResourceChanged() {
        resourceChanged = true
    }

    public void onSourceChanged() {
        sourceChanged = true
    }

    public void onFastdexPrepare() {
        //ping app
        //如果资源发生变化生成
        if (!isInstantRunBuild()) {
            return
        }


    }

    def isInstantRunBuild() {
        String launchTaskName = project.gradle.startParameter.taskRequests.get(0).args.get(0).toString()
        boolean result = launchTaskName.endsWith("fastdex${fastdexVariant.variantName}")
        if (fastdexVariant.configuration.debug) {
            project.logger.error("==fastdex launchTaskName: ${launchTaskName}")
        }
        return result
    }

    def isInstantRunEnable() {
        //启动的任务是fastdexXXX  补丁构建  设备不为空
        return isInstantRunBuild() && fastdexVariant.hasDexCache
    }
}
