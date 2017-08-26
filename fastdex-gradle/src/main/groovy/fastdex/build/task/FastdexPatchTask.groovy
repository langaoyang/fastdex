package fastdex.build.task

import fastdex.build.lib.fd.Communicator
import fastdex.build.lib.fd.ServiceCommunicator
import fastdex.build.util.FastdexInstantRun
import fastdex.build.util.FastdexUtils
import fastdex.build.util.JumpException
import fastdex.build.util.MetaInfo
import fastdex.build.variant.FastdexVariant
import fastdex.common.ShareConstants
import fastdex.common.fd.ProtocolConstants
import fastdex.common.utils.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by tong on 17/3/12.
 */
public class FastdexPatchTask extends DefaultTask {
    FastdexVariant fastdexVariant

    FastdexPatchTask() {
        group = 'fastdex'
    }

    @TaskAction
    void patch() {
        if (!fastdexVariant.hasDexCache) {
            return
        }
        FastdexInstantRun fastdexInstantRun = fastdexVariant.fastdexInstantRun
        if (!fastdexInstantRun.isInstantRunBuild()) {
            fastdexVariant.project.logger.error("==fastdex instant run disable")
            return
        }
        if (fastdexInstantRun.manifestChanged) {
            fastdexVariant.project.logger.error("==fastdex instant run disable, manifest.xml changed")
            return
        }

        boolean nothingChanged = fastdexInstantRun.nothingChanged()
        fastdexInstantRun.preparedDevice()

        def packageName = fastdexVariant.getMergedPackageName()
        ServiceCommunicator serviceCommunicator = new ServiceCommunicator(packageName)
        MetaInfo runtimeMetaInfo = null

        boolean resourceChanged = fastdexInstantRun.resourceChanged
        boolean sourceChanged = fastdexInstantRun.sourceChanged
        boolean assetsChanged = fastdexInstantRun.assetsChanged
        try {
            runtimeMetaInfo = serviceCommunicator.talkToService(fastdexInstantRun.device, new Communicator<MetaInfo>() {
                @Override
                public MetaInfo communicate(DataInputStream input, DataOutputStream output) throws IOException {
                    output.writeInt(ProtocolConstants.MESSAGE_PING_AND_SHOW_TOAST)

                    MetaInfo info = new MetaInfo()
                    info.active = input.readBoolean()
                    info.buildMillis = input.readLong()
                    info.variantName = input.readUTF()
                    //int appPid = input.readInt()

                    if (fastdexVariant.metaInfo.buildMillis != info.buildMillis) {
                        fastdexVariant.project.logger.error("==fastdex buildMillis not equal, install apk")
                        throw new JumpException()
                    }
                    if (!fastdexVariant.metaInfo.variantName.equals(info.variantName)) {
                        fastdexVariant.project.logger.error("==variantName not equal, install apk")
                        throw new JumpException()
                    }

                    if (nothingChanged) {
                        output.writeUTF("Source and resource not changed.")
                    }
                    else {
                        output.writeUTF(" ")
                    }
                    return info
                }
            })

        } catch (JumpException e) {

        } catch (Throwable e) {
            if (fastdexVariant.configuration.debug) {
                e.printStackTrace()
            }
            fastdexVariant.project.logger.error("==fastdex ping installed app fail: " + e.message)
            return
        }
        project.logger.error("==fastdex receive: ${runtimeMetaInfo}")

        if (nothingChanged) {
            fastdexInstantRun.setInstallApk(false)
            return
        }


        File resourcesApk = fastdexInstantRun.getResourcesApk()
        File mergedPatchDex = FastdexUtils.getMergedPatchDex(fastdexVariant.project,fastdexVariant.variantName)
        File patchDex = FastdexUtils.getPatchDexFile(fastdexVariant.project,fastdexVariant.variantName)

        int changeCount = 0
        if (resourceChanged || assetsChanged) {
            changeCount += 1
        }

        if (sourceChanged) {
            if (FileUtils.isLegalFile(mergedPatchDex)) {
                changeCount += 1
            }
            if (FileUtils.isLegalFile(patchDex)) {
                changeCount += 1
            }
        }

        long start = System.currentTimeMillis()
        try {
            boolean result = serviceCommunicator.talkToService(fastdexInstantRun.device, new Communicator<Boolean>() {
                @Override
                public Boolean communicate(DataInputStream input, DataOutputStream output) throws IOException {
                    output.writeInt(ProtocolConstants.MESSAGE_PATCHES)
                    output.writeLong(0L)
                    output.writeInt(changeCount)

                    if (resourceChanged || assetsChanged) {
                        project.logger.error("==fastdex write ${ShareConstants.RESOURCE_APK_FILE_NAME}")
                        output.writeUTF(ShareConstants.RESOURCE_APK_FILE_NAME)
                        byte[] bytes = FileUtils.readContents(resourcesApk)
                        output.writeInt(bytes.length)
                        output.write(bytes)
                    }

                    if (sourceChanged) {
                        if (FileUtils.isLegalFile(mergedPatchDex)) {
                            project.logger.error("==fastdex write ${mergedPatchDex}")
                            output.writeUTF(ShareConstants.MERGED_PATCH_DEX)
                            byte[] bytes = FileUtils.readContents(mergedPatchDex)
                            output.writeInt(bytes.length)
                            output.write(bytes)
                        }
                        if (FileUtils.isLegalFile(patchDex)) {
                            project.logger.error("==fastdex write ${patchDex}")
                            output.writeUTF(ShareConstants.PATCH_DEX)
                            byte[] bytes = FileUtils.readContents(patchDex)
                            output.writeInt(bytes.length)
                            output.write(bytes)
                        }
                    }

                    output.writeInt(ProtocolConstants.UPDATE_MODE_WARM_SWAP)
                    output.writeBoolean(true)

                    input.readBoolean()

                    try {
                        return input.readBoolean()
                    } catch (Throwable e) {
                        return false
                    }
                }
            })
            long end = System.currentTimeMillis();
            project.logger.error("==fastdex send patch data success. use: ${end - start}ms")

            //kill app
            killApp()
            fastdexInstantRun.startBootActivity()

//            if (sourceChanged) {
//                //kill app
//                killApp()
//                fastdexInstantRun.startBootActivity()
//            }
//            else {
//                if (!runtimeMetaInfo.active || !result) {
//                    killApp()
//                    startBootActivity()
//                }
//            }
            fastdexInstantRun.setInstallApk(false)
        } catch (Throwable e) {

            e.printStackTrace()
        }

        println("##############哈哈哈 resourceChanged: ${fastdexInstantRun.resourceChanged}, sourceChanged: ${fastdexInstantRun.sourceChanged}, manifestChanged: ${fastdexInstantRun.manifestChanged}, assetsChanged: ${fastdexInstantRun.assetsChanged}")
    }

    def killApp() {
        //adb shell am force-stop 包名
        def packageName = fastdexVariant.getMergedPackageName()
        //$ adb shell kill {appPid}
        def process = new ProcessBuilder(FastdexUtils.getAdbCmdPath(project),"shell","am","force-stop","${packageName}").start()
        int status = process.waitFor()
        try {
            process.destroy()
        } catch (Throwable e) {

        }

        String cmd = "adb shell am force-stop ${packageName}"
        if (fastdexVariant.configuration.debug) {
            project.logger.error("${cmd}")
        }
        if (status != 0) {
            throw new RuntimeException("==fastdex kill app fail: \n${cmd}")
        }
    }
}
